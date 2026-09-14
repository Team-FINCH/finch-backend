package com.finch.domain.price.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.InterestRegistry;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.config.FinchProperties;
import com.finch.global.util.MarketClock;
import com.finch.global.util.StockUniverse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code stale} 3상태(apiSpec 5.4)와 관심 신호를 실제 Redis 로 본다.
 * <p>
 * 시각은 고정한다. 실제 시간을 기다리거나 {@code sleep} 으로 흉내 내면 경계 테스트가 느리고 불안정해진다. 그래서 서비스를
 * 주입받지 않고 <b>고정 {@code Clock} 으로 직접 만든다</b> — 캐시와 레지스트리는 컨테이너의 진짜 Redis 를 쓴다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PriceQueryServiceTest {

	private static final AtomicLong CODE_SEQ = new AtomicLong(700_000L);
	private static final Instant NOW = Instant.parse("2026-09-07T05:30:00Z");

	@Autowired
	private PriceCache priceCache;

	@Autowired
	private InterestRegistry interestRegistry;

	@Autowired
	private PriceProperties properties;

	@Autowired
	private PriceQueryPort injectedPort;

	@Nested
	@DisplayName("stale 3상태")
	class Stale {

		@Test
		@DisplayName("정상 수신 — 값이 있고 stale 은 false, 등락은 계산해서 나간다")
		void fresh() {
			String code = newCode();
			priceCache.put(code, new PriceEntry(73_500L, 74_400L, NOW.minusSeconds(1)));

			PriceSnapshot snapshot = serviceAt(NOW).latest(code);

			assertThat(snapshot.stale()).isFalse();
			assertThat(snapshot.currentPrice()).isEqualTo(73_500L);
			assertThat(snapshot.changeAmount()).isEqualTo(-900L);
			assertThat(snapshot.changeRate()).isEqualByComparingTo(new BigDecimal("-1.21"));
			assertThat(snapshot.asOf()).isEqualTo(NOW.minusSeconds(1));
		}

		@Test
		@DisplayName("수신 끊김 — 허용 시간을 넘으면 마지막 값을 그대로 두고 stale 만 true 다")
		void staleKeepsLastValue() {
			String code = newCode();
			Instant old = NOW.minus(properties.staleAfter()).minusSeconds(1);
			priceCache.put(code, new PriceEntry(73_500L, 74_400L, old));

			PriceSnapshot snapshot = serviceAt(NOW).latest(code);

			assertThat(snapshot.stale()).isTrue();
			// 값을 지우지 않는다 — 화면은 "시세 지연" 을 띄우되 가격은 계속 보여준다.
			assertThat(snapshot.currentPrice()).isEqualTo(73_500L);
			assertThat(snapshot.changeAmount()).isEqualTo(-900L);
			assertThat(snapshot.asOf()).isEqualTo(old);
		}

		@Test
		@DisplayName("값 없음 — 캐시에 없으면 넷 다 null 이고 stale 은 true 다")
		void missing() {
			PriceSnapshot snapshot = serviceAt(NOW).latest(newCode());

			assertThat(snapshot.stale()).isTrue();
			assertThat(snapshot.currentPrice()).isNull();
			assertThat(snapshot.changeAmount()).isNull();
			assertThat(snapshot.changeRate()).isNull();
			assertThat(snapshot.asOf()).isNull();
		}

		/** 허용 시간 <b>정확히</b> 그 순간은 아직 신선하다. 넘어야 stale 이다. */
		@Test
		@DisplayName("경계 — 허용 시간 딱 그 시점은 stale 이 아니고 1밀리초 뒤부터 stale 이다")
		void boundary() {
			String code = newCode();
			priceCache.put(code, new PriceEntry(1_000L, 1_000L, NOW.minus(properties.staleAfter())));

			assertThat(serviceAt(NOW).latest(code).stale()).isFalse();
			assertThat(serviceAt(NOW.plusMillis(1)).latest(code).stale()).isTrue();
		}

		/**
		 * 웹소켓 티어는 체결이 올 때만 캐시를 쓴다. 장 밖에 시각 규칙을 그대로 두면 15:30 부터 30종목 전부가 "시세 지연" 이 된다 —
		 * 장 밖에서는 마지막 값이 곧 현재가라 지연이 아니다.
		 */
		@Test
		@DisplayName("장 밖(15:45 KST·주말)에서는 오래된 값도 stale 이 아니다 — 값 없음은 여전히 stale 이다")
		void notStaleOutsideSession() {
			String code = newCode();
			Instant afterClose = Instant.parse("2026-09-07T06:45:00Z"); // 월요일 15:45 KST
			Instant weekend = Instant.parse("2026-09-06T03:00:00Z");    // 일요일 12:00 KST
			priceCache.put(code, new PriceEntry(73_500L, 74_400L, afterClose.minus(Duration.ofHours(2))));

			assertThat(serviceAt(afterClose).latest(code).stale()).isFalse();
			assertThat(serviceAt(weekend).latest(code).stale()).isFalse();
			assertThat(serviceAt(afterClose).latest(newCode()).stale()).isTrue();
			// 같은 나이의 값이라도 장중이면 stale 이다 — 규칙이 사라진 게 아니라 장 밖에서만 쉰다.
			assertThat(serviceAt(NOW).latest(code).stale()).isTrue();
		}
	}

	@Nested
	@DisplayName("다건 조회")
	class Bulk {

		@Test
		@DisplayName("요청한 모든 코드가 결과에 담긴다 — 캐시에 없는 것도 값 없음으로")
		void returnsEveryRequestedCode() {
			String has = newCode();
			String missing = newCode();
			priceCache.put(has, new PriceEntry(70_000L, 70_000L, NOW));

			Map<String, PriceSnapshot> result = serviceAt(NOW).latestAll(List.of(has, missing));

			assertThat(result).containsOnlyKeys(has, missing);
			assertThat(result.get(has).currentPrice()).isEqualTo(70_000L);
			assertThat(result.get(has).stale()).isFalse();
			assertThat(result.get(missing).currentPrice()).isNull();
			assertThat(result.get(missing).stale()).isTrue();
		}

		@Test
		@DisplayName("빈 목록을 물으면 빈 결과다 — 캐시를 치지 않는다")
		void emptyRequest() {
			assertThat(serviceAt(NOW).latestAll(List.of())).isEmpty();
		}
	}

	@Nested
	@DisplayName("관심 신호")
	class Interest {

		/** apiSpec 5.6 — 묻는 것 자체가 신호다. 단건 조회도 신호라서 종목 상세를 보고 있는 종목도 갱신된다. */
		@Test
		@DisplayName("단건·다건 조회 모두 관심 신호를 남긴다")
		void bothLeaveSignal() {
			String single = newCode();
			String bulkA = newCode();
			String bulkB = newCode();

			serviceAt(NOW).latest(single);
			serviceAt(NOW).latestAll(List.of(bulkA, bulkB));

			assertThat(interestRegistry.interested()).contains(single, bulkA, bulkB);
		}
	}

	@Test
	@DisplayName("주입되는 PriceQueryPort 구현이 이 서비스다 — EmptyPriceQueryPort 는 지웠다")
	void portIsBackedByThisService() {
		assertThat(injectedPort).isInstanceOf(PriceQueryService.class);
	}

	// ---- helpers ----

	/** 장 시계도 같은 고정 시각으로 만든다 — stale 판정이 "지금" 을 한 번 잡아 두 규칙에 같이 쓰기 때문이다. always-open 은 끈다. */
	private PriceQueryService serviceAt(Instant instant) {
		Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
		FinchProperties finch = new FinchProperties(new FinchProperties.Market(false),
			new FinchProperties.Http(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(30)),
			new FinchProperties.LeaderLock(Duration.ofSeconds(10), Duration.ofSeconds(3)),
			new FinchProperties.Internal("test-only-internal-token"));
		return new PriceQueryService(priceCache, interestRegistry, properties, StockUniverse.unrestricted(),
			new MarketClock(finch, clock), clock);
	}

	/** 테스트마다 다른 종목코드를 쓴다 — 캐시가 컨테이너에 공유돼 앞 테스트의 값이 남는다. */
	private static String newCode() {
		return String.valueOf(CODE_SEQ.incrementAndGet());
	}
}
