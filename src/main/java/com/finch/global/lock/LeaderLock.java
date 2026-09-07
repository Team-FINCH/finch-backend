package com.finch.global.lock;

import com.finch.global.config.FinchProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * replica 중 <b>1대만</b> 배경 작업(KIS 폴링·일봉 배치)을 돌리게 하는 리더 락.
 * <p>
 * <b>왜 필요한가</b> — 앱키 하나를 두 인스턴스가 같이 쓰면 초당 한도를 둘이 나눠 쓰고, 같은 종목을 두 번 부르며, 일봉 배치가 두 번
 * 돈다. 워커를 별도 모듈로 빼는 안은 미결(backConvention §2.6)이고 기능 동결 전에 배포 형태를 늘리지 않으므로, 같은 jar 를 두 대
 * 띄우되 락으로 한 대만 일하게 한다. 못 잡은 인스턴스는 대기하다가 리더가 죽으면(TTL 만료) 다음 갱신 주기에 이어받는다.
 * <p>
 * <b>{@code global} 에 있는 이유</b> — 쓰는 쪽이 price(폴링)와 stock(일봉 배치)인데 둘은 같은 1층이라 한쪽이 다른 쪽 것을 import 할 수
 * 없다 (backConvention 2.4 규칙 2). Redis 만 아는 순수 인프라라 "global 은 도메인을 참조하지 않는다" 에도 맞는다.
 * <p>
 * 구현은 {@code SET key instanceId NX PX ttl} 이고, 갱신은 <b>내 값일 때만</b> TTL 을 늘리는 Lua 스크립트다 — 남의 락을 연장하거나
 * 지우는 일이 없게. 갱신 주기(3초)는 TTL(10초)보다 충분히 짧아 리더가 살아 있는 한 놓치지 않는다. 시계는 Redis 것 하나라
 * 인스턴스 간 시각 차이가 끼어들 자리가 없다.
 */
@Slf4j
@Component
public class LeaderLock implements SmartLifecycle {

	static final String KEY = "leader:worker";
	private static final DefaultRedisScript<Long> RENEW_IF_OWNER = new DefaultRedisScript<>("""
		if redis.call('get', KEYS[1]) == ARGV[1] then
		  return redis.call('pexpire', KEYS[1], ARGV[2])
		end
		return 0
		""", Long.class);
	private static final DefaultRedisScript<Long> DELETE_IF_OWNER = new DefaultRedisScript<>("""
		if redis.call('get', KEYS[1]) == ARGV[1] then
		  return redis.call('del', KEYS[1])
		end
		return 0
		""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final Duration ttl;
	private final Duration renewInterval;
	private final String instanceId;
	private volatile boolean leader;
	private volatile boolean running;
	private ScheduledExecutorService scheduler;

	@Autowired
	public LeaderLock(StringRedisTemplate redisTemplate, FinchProperties properties) {
		this(redisTemplate, properties.leaderLock().ttl(), properties.leaderLock().renewInterval(),
			UUID.randomUUID().toString());
	}

	/** 인스턴스 둘을 흉내 내는 테스트가 식별자를 직접 준다. */
	public LeaderLock(StringRedisTemplate redisTemplate, Duration ttl, Duration renewInterval, String instanceId) {
		this.redisTemplate = redisTemplate;
		this.ttl = ttl;
		this.renewInterval = renewInterval;
		this.instanceId = instanceId;
	}

	/** 지금 이 인스턴스가 리더인가. 배경 작업은 매 실행 전에 이걸 본다 — 리더가 바뀌면 다음 주기부터 조용히 멈춘다. */
	public boolean isLeader() {
		return leader;
	}

	public String instanceId() {
		return instanceId;
	}

	/**
	 * 한 주기 — 리더면 갱신, 아니면 획득 시도. 스케줄러가 부르고 테스트는 직접 부른다.
	 *
	 * @return 이 호출 뒤의 리더 여부.
	 */
	public boolean tryAcquireOrRenew() {
		try {
			if (leader) {
				Long renewed = redisTemplate.execute(RENEW_IF_OWNER, java.util.List.of(KEY), instanceId,
					String.valueOf(ttl.toMillis()));
				if (renewed == null || renewed == 0) {
					// 내 값이 아니다 — TTL 이 지나 남이 가져갔다. 다음 주기에 다시 시도한다.
					leader = false;
					log.warn("리더 락을 잃었다 instance={}", instanceId);
				}
				return leader;
			}
			Boolean acquired = redisTemplate.opsForValue().setIfAbsent(KEY, instanceId, ttl);
			if (Boolean.TRUE.equals(acquired)) {
				leader = true;
				log.info("리더 락 획득 instance={} ttl={}", instanceId, ttl);
			}
			return leader;
		} catch (RuntimeException e) {
			// Redis 가 잠깐 죽었다. 리더 자격을 유지한 채 다음 주기에 다시 본다 — 여기서 내려놓으면 둘 다 멈춘다.
			log.warn("리더 락 갱신 실패 — 다음 주기에 다시 시도한다 instance={}", instanceId, e);
			return leader;
		}
	}

	/** 내 락만 지운다. 종료 시 다음 인스턴스가 TTL 을 기다리지 않게. */
	public void release() {
		if (!leader) {
			return;
		}
		try {
			redisTemplate.execute(DELETE_IF_OWNER, java.util.List.of(KEY), instanceId);
		} catch (RuntimeException e) {
			log.warn("리더 락 반납 실패 — TTL 로 풀린다 instance={}", instanceId, e);
		} finally {
			leader = false;
		}
	}

	// ---- SmartLifecycle ----

	@Override
	public void start() {
		if (running) {
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "leader-lock");
			thread.setDaemon(true);
			return thread;
		});
		long interval = Math.max(100, renewInterval.toMillis());
		scheduler.scheduleWithFixedDelay(this::tryAcquireOrRenew, 0, interval, TimeUnit.MILLISECONDS);
		running = true;
	}

	@Override
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
		release();
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** 공급자·배치보다 먼저 시작하고 나중에 멈춘다 — 그들이 {@link #isLeader()} 를 물을 때 이미 판정이 있어야 한다. */
	@Override
	public int getPhase() {
		return Integer.MIN_VALUE + 1000;
	}
}
