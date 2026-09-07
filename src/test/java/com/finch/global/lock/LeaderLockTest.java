package com.finch.global.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 인스턴스 둘을 흉내 낸다 — 같은 Redis 에 {@link LeaderLock} 두 개. 컨텍스트의 진짜 {@code LeaderLock} 빈이 락을 쥐고 있으므로
 * 테스트 동안 그 빈을 멈추고({@code stop}) 끝나면 다시 켠다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeaderLockTest {

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private LeaderLock contextLock;

	private LeaderLock a;
	private LeaderLock b;

	@BeforeEach
	void isolate() {
		contextLock.stop();
		redisTemplate.delete(LeaderLock.KEY);
		a = new LeaderLock(redisTemplate, Duration.ofSeconds(10), Duration.ofSeconds(3), "instance-a");
		b = new LeaderLock(redisTemplate, Duration.ofSeconds(10), Duration.ofSeconds(3), "instance-b");
	}

	@AfterEach
	void restore() {
		a.release();
		b.release();
		contextLock.start();
	}

	@Test
	@DisplayName("둘이 동시에 있으면 하나만 리더다. 리더가 반납하면 다른 쪽이 이어받는다")
	void onlyOneLeaderAtATime() {
		assertThat(a.tryAcquireOrRenew()).isTrue();
		assertThat(b.tryAcquireOrRenew()).isFalse();
		assertThat(a.isLeader()).isTrue();
		assertThat(b.isLeader()).isFalse();
		assertThat(redisTemplate.opsForValue().get(LeaderLock.KEY)).isEqualTo("instance-a");

		a.release();

		assertThat(a.isLeader()).isFalse();
		assertThat(b.tryAcquireOrRenew()).isTrue();
		assertThat(redisTemplate.opsForValue().get(LeaderLock.KEY)).isEqualTo("instance-b");
	}

	@Test
	@DisplayName("갱신은 내 락일 때만 TTL 을 늘리고, 남이 가져갔으면 리더 자격을 내려놓는다")
	void renewOnlyOwnLock() {
		assertThat(a.tryAcquireOrRenew()).isTrue();
		assertThat(a.tryAcquireOrRenew()).isTrue();
		assertThat(redisTemplate.getExpire(LeaderLock.KEY)).isBetween(8L, 10L);

		// TTL 만료를 흉내 낸다 — 남이 그 자리를 차지했다.
		redisTemplate.opsForValue().set(LeaderLock.KEY, "instance-b");
		assertThat(a.tryAcquireOrRenew()).isFalse();
		assertThat(a.isLeader()).isFalse();
		assertThat(redisTemplate.opsForValue().get(LeaderLock.KEY)).isEqualTo("instance-b");

		// 남의 락은 반납으로도 지우지 않는다.
		a.release();
		assertThat(redisTemplate.opsForValue().get(LeaderLock.KEY)).isEqualTo("instance-b");
	}
}
