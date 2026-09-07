package com.finch.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.stock.dto.request.CandlePeriod;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.dto.response.StockDetailRes;
import com.finch.domain.stock.dto.response.StockSearchRes;
import com.finch.domain.stock.dto.response.TradabilityRes;
import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.event.StockSearchedEvent;
import com.finch.domain.stock.event.StockViewedEvent;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.repository.DailyCandleRepository;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검색·상세·캔들이 실제 DB 에서 무엇을 돌려주는지 본다. 데이터는 <b>기동 시 적재된 시드</b>다 — 테스트 설정이 {@code source: csv} 라
 * {@code StockStartupRunner} 가 {@code seed.csv} 300 종목과 {@code candles-seed.csv} 5종목 250 영업일을 넣는다. 그래서 첫 테스트가
 * "기동 시 빈 테이블 적재 1회"를 겸한다.
 * <p>
 * 포트는 전부 기본 빈(빈 값)이다 — 시세 null, 보유 없음, 관심 false. S6~S8 이 구현을 붙이면 그쪽 테스트가 채워진 값을 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class StockServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(980_000_000L);

	/**
	 * <b>실제로 존재하는 사용자여야 한다.</b> 상수 42 를 쓰다가 S6 에서 깨졌다 — 그때 recent 도메인이 검색·조회 이벤트를 받아
	 * {@code recent_search_keyword} 에 INSERT 하기 시작했고, 없는 사용자라 FK 제약에 걸렸다. 이벤트에 소비자가 붙으면 발행 쪽
	 * 테스트의 가짜 식별자가 드러난다.
	 */
	private long userId;

	@Autowired
	private StockService stockService;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private DailyCandleRepository dailyCandleRepository;

	@Autowired
	private StockStartupRunner startupRunner;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private ApplicationEvents events;

	@Autowired
	private UserRepository userRepository;

	@BeforeEach
	void createUser() {
		userId = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg")).getId();
	}

	@Nested
	@DisplayName("기동 적재")
	class Startup {

		@Test
		@DisplayName("기동 시 빈 테이블에 시드 300 종목과 일봉 1,250 봉이 1회 들어간다 — 다시 불러도 늘지 않는다")
		void seedsOnceOnStartup() {
			long stocks = stockRepository.count();
			long candles = dailyCandleRepository.count();
			assertThat(stocks).isGreaterThanOrEqualTo(300);
			assertThat(candles).isEqualTo(1250);
			assertThat(stockRepository.findById("005930")).get().satisfies(s -> {
				assertThat(s.getStockName()).isEqualTo("삼성전자");
				assertThat(s.getMarket()).isEqualTo(Market.KOSPI);
				assertThat(s.isActive()).isTrue();
				assertThat(s.getPreviousClose()).isPositive();
			});

			startupRunner.onReady();

			assertThat(stockRepository.count()).isEqualTo(stocks);
			assertThat(dailyCandleRepository.count()).isEqualTo(candles);
		}
	}

	@Nested
	@DisplayName("검색")
	class Search {

		@Test
		@DisplayName("이름 부분 일치 — 시세는 기본 포트라 null 이고 검색 이벤트가 발행된다")
		void searchesByNameFragment() {
			StockSearchRes res = stockService.search(userId, "삼성", 10);

			assertThat(res.items()).isNotEmpty().hasSizeLessThanOrEqualTo(10);
			assertThat(res.items()).allMatch(i -> i.stockName().contains("삼성"));
			assertThat(res.items()).extracting(StockSearchRes.Item::stockCode).contains("005930");
			assertThat(res.items()).allMatch(i -> i.currentPrice() == null && i.changeAmount() == null && i.changeRate() == null);
			assertThat(events.stream(StockSearchedEvent.class)).hasSize(1)
				.first().satisfies(e -> {
					assertThat(e.userId()).isEqualTo(userId);
					assertThat(e.keyword()).isEqualTo("삼성");
				});
		}

		@Test
		@DisplayName("코드 접두 일치가 이름 일치보다 앞이다")
		void codePrefixFirst() {
			StockSearchRes res = stockService.search(userId, "0059", 10);

			assertThat(res.items()).isNotEmpty();
			assertThat(res.items().getFirst().stockCode()).startsWith("0059");
			assertThat(res.items()).allMatch(i -> i.stockCode().startsWith("0059") || i.stockName().contains("0059"));
		}

		@Test
		@DisplayName("이름 접두 일치가 중간 일치보다 앞이다 — 자동완성은 '삼성' 을 치면 '삼성전자' 가 위다")
		void namePrefixBeforeInfix() {
			StockSearchRes res = stockService.search(userId, "전자", 10);

			List<String> names = res.items().stream().map(StockSearchRes.Item::stockName).toList();
			int firstInfix = -1;
			int lastPrefix = -1;
			for (int i = 0; i < names.size(); i++) {
				if (names.get(i).startsWith("전자")) {
					lastPrefix = i;
				} else if (firstInfix < 0) {
					firstInfix = i;
				}
			}
			if (lastPrefix >= 0 && firstInfix >= 0) {
				assertThat(lastPrefix).isLessThan(firstInfix);
			}
			assertThat(names).allMatch(n -> n.contains("전자"));
		}

		@Test
		@DisplayName("size 만큼만 준다")
		void limitsBySize() {
			assertThat(stockService.search(userId, "삼성", 2).items()).hasSize(2);
		}

		/** 컨트롤러의 @Size 는 공백을 세므로 " 삼" 을 통과시킨다. 서비스가 걷어낸 뒤 다시 본다. */
		@Test
		@DisplayName("공백을 걷어낸 검색어가 2글자 미만이면 INVALID_REQUEST 이고 이벤트는 없다")
		void rejectsShortKeywordAfterTrim() {
			assertThatThrownBy(() -> stockService.search(userId, " 삼 ", 10))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ce = (CustomException) e;
					assertThat(ce.getErrorCode()).isEqualTo(GeneralErrorCode.INVALID_REQUEST);
					assertThat(ce.getDetail()).isEqualTo(Map.of("keyword", "2글자 이상 입력해 주세요"));
				});
			assertThat(events.stream(StockSearchedEvent.class)).isEmpty();
		}

		@Test
		@DisplayName("상장폐지(is_active=false) 종목은 검색에서 빠진다 — 응답에 구분 필드는 없다")
		void excludesInactive() {
			String code = "ZZ9901";
			transactionTemplate.executeWithoutResult(s -> stockRepository.save(
				Stock.of(code, "테스트폐지전자", Market.KOSDAQ, false, null, 1_000L, Instant.now())));
			assertThat(stockService.search(userId, "테스트폐지", 10).items()).extracting(StockSearchRes.Item::stockCode)
				.contains(code);

			transactionTemplate.executeWithoutResult(s ->
				stockRepository.findById(code).orElseThrow().deactivate(Instant.now()));

			assertThat(stockService.search(userId, "테스트폐지", 10).items()).extracting(StockSearchRes.Item::stockCode)
				.doesNotContain(code);
		}

		@Test
		@DisplayName("결과 없음은 빈 목록이다 — 에러가 아니다")
		void emptyResult() {
			assertThat(stockService.search(userId, "없는종목이름XYZ", 10).items()).isEmpty();
		}
	}

	@Nested
	@DisplayName("상세")
	class Detail {

		@Test
		@DisplayName("시드 종목 — holding null · watched false · 시세 null · asOf null, 조회 이벤트가 발행된다")
		void returnsDetailWithEmptyPorts() {
			StockDetailRes res = stockService.detail(userId, "005930");

			assertThat(res.stockCode()).isEqualTo("005930");
			assertThat(res.stockName()).isEqualTo("삼성전자");
			assertThat(res.market()).isEqualTo(Market.KOSPI);
			assertThat(res.previousClose()).isPositive();
			assertThat(res.currentPrice()).isNull();
			assertThat(res.changeAmount()).isNull();
			assertThat(res.changeRate()).isNull();
			assertThat(res.asOf()).isNull();
			assertThat(res.suspended()).isFalse();
			assertThat(res.suspendedReason()).isNull();
			assertThat(res.watched()).isFalse();
			assertThat(res.holding()).isNull();
			assertThat(events.stream(StockViewedEvent.class)).hasSize(1)
				.first().satisfies(e -> {
					assertThat(e.userId()).isEqualTo(userId);
					assertThat(e.stockCode()).isEqualTo("005930");
					assertThat(e.viewedAt()).isNotNull();
				});
		}

		@Test
		@DisplayName("없는 코드는 STOCK_NOT_FOUND 이고 이벤트는 없다")
		void notFound() {
			assertThatThrownBy(() -> stockService.detail(userId, "999999"))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
			assertThat(events.stream(StockViewedEvent.class)).isEmpty();
		}

		@Test
		@DisplayName("상장폐지 종목은 상세도 404 다 — 검색에서 빠진 종목이 상세에서만 보이면 화면이 갈 곳이 없다")
		void inactiveIsNotFound() {
			String code = "ZZ9902";
			transactionTemplate.executeWithoutResult(s -> {
				Stock stock = stockRepository.save(Stock.of(code, "폐지종목", Market.KOSDAQ, false, null, 1_000L, Instant.now()));
				stock.deactivate(Instant.now());
			});

			assertThatThrownBy(() -> stockService.detail(userId, code)).isInstanceOf(CustomException.class);
		}
	}

	@Nested
	@DisplayName("캔들")
	class Candles {

		@Test
		@DisplayName("1M 은 오늘(KST)부터 30 달력일 안의 일봉을 오래된 날부터 준다")
		void oneMonthRange() {
			CandleRes res = stockService.candles("005930", CandlePeriod.ONE_MONTH);

			LocalDate today = LocalDate.now(KstTime.ZONE);
			assertThat(res.stockCode()).isEqualTo("005930");
			assertThat(res.period()).isEqualTo("1M");
			assertThat(res.interval()).isEqualTo("DAY");
			assertThat(res.candles()).isNotEmpty();
			assertThat(res.candles()).extracting(CandleRes.Candle::date)
				.allMatch(d -> !d.isBefore(today.minusDays(30)) && !d.isAfter(today))
				.isSorted();
			assertThat(res.candles()).allMatch(c -> c.high() >= c.low() && c.high() >= c.open() && c.high() >= c.close()
				&& c.low() <= c.open() && c.low() <= c.close() && c.volume() >= 0);
		}

		@Test
		@DisplayName("기간이 길수록 봉이 많다 — 1M ≤ 3M ≤ 1Y, 1Y 는 시드 전부(250 영업일 근처)")
		void longerPeriodHasMoreCandles() {
			int m1 = stockService.candles("005930", CandlePeriod.ONE_MONTH).candles().size();
			int m3 = stockService.candles("005930", CandlePeriod.THREE_MONTHS).candles().size();
			int y1 = stockService.candles("005930", CandlePeriod.ONE_YEAR).candles().size();

			assertThat(m1).isLessThanOrEqualTo(m3);
			assertThat(m3).isLessThanOrEqualTo(y1);
			assertThat(y1).isBetween(200, 250);
		}

		@Test
		@DisplayName("봉이 없는 종목은 빈 배열이고, 없는 종목은 STOCK_NOT_FOUND 다")
		void emptyAndNotFound() {
			// 시드에 있지만 일봉 시드 5종목이 아닌 것 — 두산(000150).
			assertThat(stockService.candles("000150", CandlePeriod.ONE_YEAR).candles()).isEmpty();
			assertThatThrownBy(() -> stockService.candles("999999", CandlePeriod.ONE_MONTH))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("주문용 판정")
	class Tradable {

		@Test
		@DisplayName("활성 종목은 exists, 없는 종목은 missing, 거래정지는 사유와 함께")
		void tradability() {
			assertThat(stockService.getTradable("005930")).isEqualTo(new TradabilityRes(true, "삼성전자", false, null));
			assertThat(stockService.getTradable("999999")).isEqualTo(TradabilityRes.missing());

			String code = "ZZ9903";
			transactionTemplate.executeWithoutResult(s -> stockRepository.save(
				Stock.of(code, "정지종목", Market.KOSPI, true, "거래정지", 1_000L, Instant.now())));
			assertThat(stockService.getTradable(code)).isEqualTo(new TradabilityRes(true, "정지종목", true, "거래정지"));
		}
	}
}
