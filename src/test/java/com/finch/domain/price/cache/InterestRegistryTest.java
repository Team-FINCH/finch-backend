package com.finch.domain.price.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.PriceProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 관심 신호의 두 자료 구조가 함께 동작하는지 본다 — 만료는 개별 키의 TTL 이, 목록은 SET 이 맡고, 읽을 때 둘을 대조한다.
 * 실제 Redis 로 확인한다. TTL 을 기다리지 않고 마커 키를 직접 지워 만료를 흉내 낸다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InterestRegistryTest {

	private static final AtomicLong CODE_SEQ = new AtomicLong(710_000L);

	@Autowired
	private InterestRegistry interestRegistry;

	@Autowired
	private PriceProperties properties;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Test
	@DisplayName("touch 한 종목이 목록에 들어가고 마커 키에 TTL 이 걸린다")
	void touchRegistersWithTtl() {
		String code = newCode();

		interestRegistry.touch(List.of(code));

		assertThat(interestRegistry.interested()).contains(code);
		Long ttl = redisTemplate.getExpire("price:interest:" + code, TimeUnit.SECONDS);
		assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(properties.interestTtl().toSeconds());
	}

	@Test
	@DisplayName("다시 touch 하면 수명이 늘어난다 — 폴링이 계속되면 회수되지 않는다")
	void touchExtendsTtl() {
		String code = newCode();
		interestRegistry.touch(List.of(code));
		redisTemplate.expire("price:interest:" + code, 2, TimeUnit.SECONDS);

		interestRegistry.touch(List.of(code));

		Long ttl = redisTemplate.getExpire("price:interest:" + code, TimeUnit.SECONDS);
		assertThat(ttl).isNotNull().isGreaterThan(2L);
	}

	/**
	 * SET 원소에는 TTL 을 걸 수 없어 만료된 종목이 목록에 남는다. {@code interested()} 가 대조하며 지우는 것이 그 설계다 —
	 * 청소 배치를 따로 두면 리더 락 같은 것이 또 필요해진다.
	 */
	@Test
	@DisplayName("마커가 만료되면 목록에서 빠지고 인덱스에서도 지워진다")
	void expiredCodeIsPrunedFromIndex() {
		String alive = newCode();
		String expired = newCode();
		interestRegistry.touch(List.of(alive, expired));
		assertThat(interestRegistry.interested()).contains(alive, expired);

		// TTL 을 기다리지 않고 마커만 지운다 — 만료와 같은 상태다.
		redisTemplate.delete("price:interest:" + expired);

		assertThat(interestRegistry.interested()).contains(alive).doesNotContain(expired);
		// 인덱스에서도 빠졌다 — 다음 조회가 같은 코드를 또 대조하지 않는다.
		assertThat(redisTemplate.opsForSet().isMember("price:interest:index", expired)).isFalse();
	}

	@Test
	@DisplayName("빈 목록을 touch 하면 아무 일도 하지 않는다")
	void emptyTouchIsNoop() {
		interestRegistry.touch(List.of());
	}

	private static String newCode() {
		return String.valueOf(CODE_SEQ.incrementAndGet());
	}
}
