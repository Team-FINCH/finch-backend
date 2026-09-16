package com.finch.domain.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.port.HoldingQueryPort;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.domain.stock.port.WatchlistQueryPort;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.domain.watchlist.dto.request.WatchlistSort;
import com.finch.domain.watchlist.dto.response.WatchlistRes;
import com.finch.domain.watchlist.exception.WatchlistErrorCode;
import com.finch.global.exception.CustomException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 관심 종목이 실제 DB 에서 무엇을 남기는지, 판정 순서가 apiSpec 11.2 표와 맞는지 본다.
 * <p>
 * 시세·보유 포트는 목으로 바꾼다. 기본 빈은 전부 빈 값이라 {@code CHANGE_RATE} 정렬과 {@code held} 뱃지를 확인할 수 없다 —
 * 그 둘이 이 스토리가 정한 규칙이므로 값을 넣어 본다. 나머지는 실제 DB 다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WatchlistServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(970_000_000L);

	@Autowired
	private WatchlistService watchlistService;

	@Autowired
	private WatchlistQueryPort watchlistQueryPort;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private UserRepository userRepository;

	@MockitoBean
	private PriceQueryPort priceQueryPort;

	@MockitoBean
	private HoldingQueryPort holdingQueryPort;

	@Nested
	@DisplayName("등록")
	class Add {

		@Test
		@DisplayName("담으면 목록에 나온다 — registeredAt 은 KST 이고 count·maxCount 가 함께 나간다")
		void addsItem() {
			Long userId = newUserId();
			givenNoPrices();

			watchlistService.add(userId, "005930");

			WatchlistRes res = watchlistService.list(userId, WatchlistSort.REGISTERED);
			assertThat(res.count()).isEqualTo(1);
			assertThat(res.maxCount()).isEqualTo(WatchlistService.MAX_ITEMS);
			assertThat(res.items()).hasSize(1);
			assertThat(res.items().getFirst().stockCode()).isEqualTo("005930");
			assertThat(res.items().getFirst().stockName()).isEqualTo("삼성전자");
			assertThat(res.items().getFirst().registeredAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
			// v0.8.20 (이슈 #67). 프로젝션이 실제 DB 에서 매핑되는지까지 본다 — market 은 VARCHAR 이고 받는 쪽은 String 이다.
			assertThat(res.items().getFirst().market()).isEqualTo("KOSPI");
			assertThat(res.items().getFirst().suspended()).isFalse();
		}

		@Test
		@DisplayName("판정 1 — 없는 종목은 STOCK_NOT_FOUND")
		void unknownStock() {
			Long userId = newUserId();

			assertThatThrownBy(() -> watchlistService.add(userId, "999999"))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
		}

		@Test
		@DisplayName("판정 1 — 상장폐지 종목도 STOCK_NOT_FOUND. 거래정지 종목은 담을 수 있다")
		void inactiveVsSuspended() {
			Long userId = newUserId();
			givenNoPrices();
			String inactive = "ZZ8801";
			String suspended = "ZZ8802";
			stockRepository.saveAndFlush(Stock.of(suspended, "정지종목", com.finch.domain.stock.entity.Market.KOSPI,
				true, "거래정지", 1_000L, Instant.now()));
			Stock dead = stockRepository.saveAndFlush(Stock.of(inactive, "폐지종목",
				com.finch.domain.stock.entity.Market.KOSPI, false, null, 1_000L, Instant.now()));
			dead.deactivate(Instant.now());
			stockRepository.saveAndFlush(dead);

			assertThatThrownBy(() -> watchlistService.add(userId, inactive)).isInstanceOf(CustomException.class);
			// 관심은 매매가 아니다 — 거래정지 종목도 담기고 화면이 뱃지로 알린다.
			watchlistService.add(userId, suspended);
			assertThat(codes(userId, WatchlistSort.REGISTERED)).containsExactly(suspended);
			// 그 뱃지의 근거가 응답에 실려야 한다 (v0.8.20, 이슈 #67). 화면이 종목 상세를 따로 부르면
			// 그 호출이 "최근 본 종목" 을 덮으므로 여기서 주는 것이 유일한 경로다.
			WatchlistRes.Item item = watchlistService.list(userId, WatchlistSort.REGISTERED).items().getFirst();
			assertThat(item.suspended()).isTrue();
			assertThat(item.market()).isEqualTo("KOSPI");
		}

		@Test
		@DisplayName("판정 2 — 이미 담긴 종목은 WATCHLIST_ALREADY_EXISTS")
		void duplicate() {
			Long userId = newUserId();
			givenNoPrices();
			watchlistService.add(userId, "005930");

			assertThatThrownBy(() -> watchlistService.add(userId, "005930"))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(WatchlistErrorCode.WATCHLIST_ALREADY_EXISTS);
			assertThat(codes(userId, WatchlistSort.REGISTERED)).hasSize(1);
		}

		@Test
		@DisplayName("판정 3 — 50개가 차면 WATCHLIST_LIMIT_EXCEEDED")
		void limit() {
			Long userId = newUserId();
			givenNoPrices();
			List<String> codes = someCodes(WatchlistService.MAX_ITEMS + 1);
			codes.subList(0, WatchlistService.MAX_ITEMS).forEach(code -> watchlistService.add(userId, code));

			assertThatThrownBy(() -> watchlistService.add(userId, codes.getLast()))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(WatchlistErrorCode.WATCHLIST_LIMIT_EXCEEDED);
			assertThat(codes(userId, WatchlistSort.REGISTERED)).hasSize(WatchlistService.MAX_ITEMS);
		}

		/** apiSpec 11.2 — "이미 등록된 종목은 한도가 찼어도 ALREADY_EXISTS". 중복 검사가 한도 검사보다 앞이라는 뜻이다. */
		@Test
		@DisplayName("판정 순서 — 50개가 찬 상태에서 이미 담긴 종목을 다시 담으면 LIMIT 이 아니라 ALREADY_EXISTS")
		void duplicateBeatsLimit() {
			Long userId = newUserId();
			givenNoPrices();
			List<String> codes = someCodes(WatchlistService.MAX_ITEMS);
			codes.forEach(code -> watchlistService.add(userId, code));

			assertThatThrownBy(() -> watchlistService.add(userId, codes.getFirst()))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(WatchlistErrorCode.WATCHLIST_ALREADY_EXISTS);
		}
	}

	@Nested
	@DisplayName("해제와 포트")
	class RemoveAndPort {

		@Test
		@DisplayName("해제는 멱등이다 — 담긴 적 없어도 예외가 없다. 토글이라 그것이 정상 경로다")
		void removeIsIdempotent() {
			Long userId = newUserId();
			givenNoPrices();
			watchlistService.add(userId, "005930");

			watchlistService.remove(userId, "005930");
			assertThat(codes(userId, WatchlistSort.REGISTERED)).isEmpty();

			watchlistService.remove(userId, "005930");
			watchlistService.remove(userId, "999999");
		}

		/** 이 MR 이 {@code EmptyWatchlistQueryPort} 를 지우고 실제 구현을 넣었다 — 종목 상세의 {@code watched} 가 실제 값이 된다. */
		@Test
		@DisplayName("WatchlistQueryPort 가 실제 등록 여부를 답한다")
		void portReflectsRegistration() {
			Long userId = newUserId();
			givenNoPrices();
			assertThat(watchlistQueryPort.isWatched(userId, "005930")).isFalse();

			watchlistService.add(userId, "005930");
			assertThat(watchlistQueryPort.isWatched(userId, "005930")).isTrue();
			assertThat(watchlistQueryPort.isWatched(userId, "000660")).isFalse();

			watchlistService.remove(userId, "005930");
			assertThat(watchlistQueryPort.isWatched(userId, "005930")).isFalse();
		}

		@Test
		@DisplayName("다른 사용자의 관심 종목은 보이지 않고 해제되지도 않는다")
		void isolatedPerUser() {
			Long mine = newUserId();
			Long other = newUserId();
			givenNoPrices();
			watchlistService.add(mine, "005930");
			watchlistService.add(other, "005930");

			watchlistService.remove(mine, "005930");

			assertThat(codes(mine, WatchlistSort.REGISTERED)).isEmpty();
			assertThat(codes(other, WatchlistSort.REGISTERED)).containsExactly("005930");
			assertThat(watchlistQueryPort.isWatched(other, "005930")).isTrue();
		}
	}

	@Nested
	@DisplayName("정렬과 시세")
	class Sorting {

		/**
		 * <b>이 테스트가 콜레이션 결함을 잡았다.</b> DB 기본 콜레이션({@code en_US.utf8})으로 정렬하면 {@code 카카오 · 현대차 ·
		 * 삼성전자} 가 나온다 — 가나다순이 아니다. 쿼리에 {@code COLLATE "ko-KR-x-icu"} 를 넣어 고쳤고, 그 값을 여기 그대로 적어
		 * 회귀를 막는다. 자바의 {@code isSorted()} 로 단언하지 않는 이유도 같다 — 정렬 주체가 DB 라 자바 코드포인트 순서와 다르다.
		 */
		@Test
		@DisplayName("REGISTERED 는 최근 등록순, NAME 은 종목명순 — 정렬은 DB 가 한다")
		void registeredAndName() {
			Long userId = newUserId();
			givenNoPrices();
			// 이름순과 등록순이 다르도록 일부러 뒤섞어 담는다.
			watchlistService.add(userId, "005930");   // 삼성전자
			watchlistService.add(userId, "005380");   // 현대차
			watchlistService.add(userId, "035720");   // 카카오

			assertThat(codes(userId, WatchlistSort.REGISTERED)).containsExactly("035720", "005380", "005930");
			assertThat(watchlistService.list(userId, WatchlistSort.NAME).items())
				.extracting(WatchlistRes.Item::stockName)
				.containsExactly("삼성전자", "카카오", "현대차");
		}

		@Test
		@DisplayName("CHANGE_RATE 는 등락률 내림차순이고 시세 없는 종목은 뒤로 간다")
		void changeRate() {
			Long userId = newUserId();
			watchlistService.add(userId, "000660");
			watchlistService.add(userId, "005930");
			watchlistService.add(userId, "035420");
			given(priceQueryPort.latestAll(any())).willReturn(prices(Map.of(
				"005930", new BigDecimal("-1.21"),
				"000660", new BigDecimal("3.40")
				// 035420 은 값 없음
			), List.of("005930", "000660", "035420")));

			List<String> ordered = codes(userId, WatchlistSort.CHANGE_RATE);

			assertThat(ordered).containsExactly("000660", "005930", "035420");
		}

		@Test
		@DisplayName("held 는 보유 포트가 답한다 — 벌크 한 번으로 묻는다")
		void heldBadge() {
			Long userId = newUserId();
			givenNoPrices();
			watchlistService.add(userId, "005930");
			watchlistService.add(userId, "000660");
			given(holdingQueryPort.heldCodesAmong(anyLong(), any())).willReturn(Set.of("005930"));

			Map<String, Boolean> held = new LinkedHashMap<>();
			watchlistService.list(userId, WatchlistSort.REGISTERED).items()
				.forEach(item -> held.put(item.stockCode(), item.held()));

			assertThat(held).containsEntry("005930", true).containsEntry("000660", false);
		}

		/**
		 * 검색·상세·최근 본 종목이 모두 상장폐지를 거른다. 여기만 남겨 두면 탭했을 때 404 가 나는 항목이 목록에 있게 된다.
		 * 한도도 같은 기준이라야 "47 / 50 인데 왜 못 담지" 가 생기지 않는다.
		 */
		@Test
		@DisplayName("상장폐지 종목은 목록·count·한도 판정에서 모두 빠진다")
		void inactiveDisappearsFromListAndCount() {
			Long userId = newUserId();
			givenNoPrices();
			String code = "ZZ8803";
			stockRepository.saveAndFlush(Stock.of(code, "폐지예정종목",
				com.finch.domain.stock.entity.Market.KOSPI, false, null, 1_000L, Instant.now()));
			watchlistService.add(userId, code);
			watchlistService.add(userId, "005930");
			assertThat(watchlistService.list(userId, WatchlistSort.REGISTERED).count()).isEqualTo(2);

			Stock dead = stockRepository.findById(code).orElseThrow();
			dead.deactivate(Instant.now());
			stockRepository.saveAndFlush(dead);

			WatchlistRes res = watchlistService.list(userId, WatchlistSort.REGISTERED);
			assertThat(res.count()).isEqualTo(1);
			assertThat(res.items()).extracting(WatchlistRes.Item::stockCode).containsExactly("005930");
			// 행은 지우지 않는다 — 마스터에 다시 나타나면(applyMaster) 목록에도 한도에도 돌아온다.
			dead.applyMaster("폐지예정종목", com.finch.domain.stock.entity.Market.KOSPI, false, null, 1_000L,
				Instant.now());
			stockRepository.saveAndFlush(dead);
			assertThat(watchlistService.list(userId, WatchlistSort.REGISTERED).count()).isEqualTo(2);
		}

		@Test
		@DisplayName("담은 적이 없으면 빈 목록이고 count 는 0, maxCount 는 50 이다")
		void emptyList() {
			givenNoPrices();
			WatchlistRes res = watchlistService.list(newUserId(), WatchlistSort.REGISTERED);

			assertThat(res.items()).isEmpty();
			assertThat(res.count()).isZero();
			assertThat(res.maxCount()).isEqualTo(50);
		}
	}

	// ---- helpers ----

	private void givenNoPrices() {
		given(priceQueryPort.latestAll(any())).willAnswer(invocation -> {
			Map<String, PriceSnapshot> result = new LinkedHashMap<>();
			((Iterable<?>) invocation.getArgument(0)).forEach(code -> result.put((String) code, PriceSnapshot.missing()));
			return result;
		});
	}

	private static Map<String, PriceSnapshot> prices(Map<String, BigDecimal> rates, List<String> codes) {
		Map<String, PriceSnapshot> result = new LinkedHashMap<>();
		codes.forEach(code -> result.put(code, rates.containsKey(code)
			? new PriceSnapshot(70_000L, 100L, rates.get(code), Instant.now(), false)
			: PriceSnapshot.missing()));
		return result;
	}

	private List<String> codes(Long userId, WatchlistSort sort) {
		return watchlistService.list(userId, sort).items().stream().map(WatchlistRes.Item::stockCode).toList();
	}

	private List<String> someCodes(int count) {
		return stockRepository.findAll().stream()
			.filter(Stock::isActive)
			.map(Stock::getStockCode)
			.filter(code -> !code.startsWith("ZZ"))
			.sorted()
			.limit(count)
			.toList();
	}

	private Long newUserId() {
		return userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg")).getId();
	}
}
