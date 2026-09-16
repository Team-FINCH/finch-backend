package com.finch.domain.recent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.recent.dto.response.RecentViewedRes;
import com.finch.domain.recent.repository.RecentViewedStockRepository;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.event.StockViewedEvent;
import com.finch.domain.stock.controller.StockController;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.domain.stock.service.StockService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 최근 본 종목이 실제 DB 에서 무엇을 남기는지 본다. 목으로는 볼 수 없는 것들이 대상이다 — 네이티브 UPSERT 의 경합 처리, FIFO 삭제,
 * 그리고 <b>읽기 전용 트랜잭션에서 발행된 이벤트를 받아 쓰기가 되는지</b>.
 * <p>
 * 종목은 기동 시 적재된 시드 300개를 쓴다 (테스트 설정 {@code source: csv}).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecentViewedServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(950_000_000L);

	@Autowired
	private RecentViewedService recentViewedService;

	@Autowired
	private RecentViewedStockRepository recentViewedStockRepository;

	@Autowired
	private StockService stockService;

	/** 이벤트 발행 지점이라 실제 경로 테스트가 이것을 부른다 (이슈 309). */
	@Autowired
	private StockController stockController;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private UserRepository userRepository;

	@Nested
	@DisplayName("이벤트 수신")
	class Recording {

		/**
		 * <b>이 테스트가 이 스토리에서 제일 중요하다.</b> 리스너를 직접 부르지 않고 <b>실제 경로로</b> 확인한다.
		 * <p>
		 * 그 경로가 이슈 309 에서 바뀌었다. 예전에는 {@code StockService.detail} 이 {@code readOnly = true} 트랜잭션
		 * 안에서 발행했고, 리스너는 Postgres 의 INSERT 거절을 피하려고 {@code REQUIRES_NEW} 를 써야 했다. 그 결과
		 * 요청 하나가 커넥션 2개를 점유해 풀 데드락이 났다. 지금은 {@link StockController} 가 트랜잭션 밖에서 발행하므로
		 * <b>컨트롤러를 부르는 것이 곧 실제 경로다</b> — 서비스를 직접 부르면 이벤트가 아예 나가지 않는다.
		 */
		@Test
		@DisplayName("종목 상세를 보면 행이 생긴다 — 컨트롤러가 트랜잭션 밖에서 발행한 뒤 기록된다")
		void recordsThroughStockDetail() {
			Long userId = newUserId();

			stockController.detail(userId, "005930");

			List<RecentViewedRes.Item> items = recentViewedService.list(userId).items();
			assertThat(items).hasSize(1);
			assertThat(items.getFirst().stockCode()).isEqualTo("005930");
			assertThat(items.getFirst().stockName()).isEqualTo("삼성전자");
			// 시세 포트는 아직 기본 빈이라 값이 없다 (S7 에서 채워진다).
			assertThat(items.getFirst().currentPrice()).isNull();
			assertThat(items.getFirst().changeRate()).isNull();
			// apiSpec 1.1 — 시각은 KST 오프셋을 포함한다.
			assertThat(items.getFirst().viewedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		}

		@Test
		@DisplayName("같은 종목을 다시 보면 행이 늘지 않고 최상단으로 올라온다")
		void reviewMovesToTop() {
			Long userId = newUserId();
			List<String> codes = someCodes(3);
			codes.forEach(code -> view(userId, code));
			assertThat(codeList(userId)).containsExactly(codes.get(2), codes.get(1), codes.get(0));

			view(userId, codes.get(0));

			assertThat(codeList(userId)).containsExactly(codes.get(0), codes.get(2), codes.get(1));
		}

		@Test
		@DisplayName("31번째를 보면 가장 오래된 것이 빠지고 30건이 유지된다")
		void keepsThirtyNewest() {
			Long userId = newUserId();
			List<String> codes = someCodes(31);
			String oldest = codes.getFirst();

			codes.forEach(code -> view(userId, code));

			List<String> shown = codeList(userId);
			assertThat(shown).hasSize(RecentViewedService.MAX_ITEMS);
			assertThat(shown).doesNotContain(oldest);
			assertThat(shown.getFirst()).isEqualTo(codes.getLast());
		}

		/** 같은 사용자가 두 탭에서 같은 종목을 여는 상황. select-then-save 였다면 UNIQUE 위반이 났을 자리다. */
		@Test
		@DisplayName("같은 종목을 20건 동시에 봐도 행은 하나다")
		void concurrentViewsKeepOneRow() throws Exception {
			Long userId = newUserId();
			int concurrency = 20;
			CountDownLatch start = new CountDownLatch(1);
			Callable<Void> call = () -> {
				start.await();
				view(userId, "005930");
				return null;
			};

			List<Future<Void>> futures = new ArrayList<>();
			try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
				for (int i = 0; i < concurrency; i++) {
					futures.add(pool.submit(call));
				}
				start.countDown();
				for (Future<Void> future : futures) {
					future.get();
				}
			}

			assertThat(codeList(userId)).containsExactly("005930");
		}
	}

	@Nested
	@DisplayName("조회와 삭제")
	class Listing {

		@Test
		@DisplayName("본 적이 없으면 빈 목록이다 — 에러가 아니다")
		void emptyList() {
			assertThat(recentViewedService.list(newUserId()).items()).isEmpty();
		}

		@Test
		@DisplayName("개별 삭제와 전체 삭제. 없는 대상을 지워도 예외가 없다")
		void deleteIsIdempotent() {
			Long userId = newUserId();
			List<String> codes = someCodes(3);
			codes.forEach(code -> view(userId, code));

			recentViewedService.delete(userId, codes.get(1));
			assertThat(codeList(userId)).containsExactly(codes.get(2), codes.get(0));

			// 이미 지운 것을 다시, 그리고 본 적 없는 종목을 — 둘 다 조용히 끝난다.
			recentViewedService.delete(userId, codes.get(1));
			recentViewedService.delete(userId, "999999");
			assertThat(codeList(userId)).hasSize(2);

			recentViewedService.deleteAll(userId);
			assertThat(codeList(userId)).isEmpty();
			recentViewedService.deleteAll(userId);
		}

		@Test
		@DisplayName("다른 사용자의 목록은 보이지 않고, 지워지지도 않는다")
		void isolatedPerUser() {
			Long mine = newUserId();
			Long other = newUserId();
			view(mine, "005930");
			view(other, "000660");

			assertThat(codeList(mine)).containsExactly("005930");
			assertThat(codeList(other)).containsExactly("000660");

			recentViewedService.deleteAll(mine);
			assertThat(codeList(other)).containsExactly("000660");
		}

		@Test
		@DisplayName("상장폐지 종목은 목록에서 빠진다 — 검색에서 빠진 것을 여기서 보여줄 이유가 없다")
		void hidesInactiveStock() {
			Long userId = newUserId();
			view(userId, "005930");
			String code = someCodes(1).getFirst();
			view(userId, code);
			assertThat(codeList(userId)).contains(code);

			Stock stock = stockRepository.findById(code).orElseThrow();
			stock.deactivate(Instant.now());
			stockRepository.saveAndFlush(stock);

			assertThat(codeList(userId)).doesNotContain(code).contains("005930");
		}
	}

	// ---- helpers ----

	private void view(Long userId, String stockCode) {
		recentViewedService.on(new StockViewedEvent(userId, stockCode, Instant.now()));
	}

	private List<String> codeList(Long userId) {
		return recentViewedService.list(userId).items().stream().map(RecentViewedRes.Item::stockCode).toList();
	}

	/** 시드에서 활성 종목 코드를 필요한 만큼. 삼성전자는 다른 테스트가 쓰므로 제외한다. */
	private List<String> someCodes(int count) {
		return stockRepository.findAll().stream()
			.filter(Stock::isActive)
			.map(Stock::getStockCode)
			.filter(code -> !code.equals("005930") && !code.equals("000660"))
			.sorted()
			.limit(count)
			.toList();
	}

	private Long newUserId() {
		return userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg")).getId();
	}
}
