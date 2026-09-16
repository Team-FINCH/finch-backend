package com.finch.domain.recent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.recent.dto.response.RecentSearchRes;
import com.finch.domain.stock.controller.StockController;
import com.finch.domain.stock.event.StockSearchedEvent;
import com.finch.domain.stock.service.StockService;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 최근 검색어. 구조는 최근 본 종목과 같고 다른 점(문자열이라 종목과 묶지 않는다·10건)만 따로 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecentSearchServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(960_000_000L);

	@Autowired
	private RecentSearchService recentSearchService;

	@Autowired
	private StockService stockService;

	/** 이벤트 발행 지점이라 실제 경로 테스트가 이것을 부른다 (이슈 309). */
	@Autowired
	private StockController stockController;

	@Autowired
	private UserRepository userRepository;

	/**
	 * 실제 경로로 본다 — 이슈 309 이후 발행 지점은 {@link StockController} 다 ({@code RecentViewedServiceTest} 주석 참고).
	 * 서비스를 직접 부르면 이벤트가 나가지 않는다.
	 */
	@Test
	@DisplayName("검색하면 행이 생긴다 — 컨트롤러가 트랜잭션 밖에서 발행한 뒤 기록된다")
	void recordsThroughSearch() {
		Long userId = newUserId();

		stockController.search(userId, "삼성", 10);

		List<RecentSearchRes.Item> items = recentSearchService.list(userId).items();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst().keyword()).isEqualTo("삼성");
		assertThat(items.getFirst().keywordId()).isNotNull();
		assertThat(items.getFirst().searchedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
	}

	@Test
	@DisplayName("같은 검색어를 다시 치면 새 항목을 만들지 않고 시각만 갱신한다 — 최상단으로 올라온다")
	void sameKeywordIsRefreshed() {
		Long userId = newUserId();
		search(userId, "삼성");
		search(userId, "카카오");
		assertThat(keywords(userId)).containsExactly("카카오", "삼성");

		search(userId, "삼성");

		assertThat(keywords(userId)).containsExactly("삼성", "카카오");
	}

	@Test
	@DisplayName("11번째를 치면 가장 오래된 것이 빠지고 10건이 유지된다")
	void keepsTenNewest() {
		Long userId = newUserId();
		for (int i = 0; i < 11; i++) {
			search(userId, "검색어" + i);
		}

		List<String> shown = keywords(userId);
		assertThat(shown).hasSize(RecentSearchService.MAX_ITEMS);
		assertThat(shown).doesNotContain("검색어0");
		assertThat(shown.getFirst()).isEqualTo("검색어10");
	}

	@Test
	@DisplayName("개별 삭제는 keywordId 로 한다. 남의 keywordId 를 지목해도 아무 일이 없다")
	void deleteIsScopedAndIdempotent() {
		Long mine = newUserId();
		Long other = newUserId();
		search(mine, "삼성");
		search(mine, "카카오");
		search(other, "네이버");
		Long othersId = recentSearchService.list(other).items().getFirst().keywordId();
		Long mineId = recentSearchService.list(mine).items().getLast().keywordId();

		// 남의 것을 지목 — 예외 없이 아무 일도 일어나지 않는다 (존재 여부를 알려주지 않는다).
		recentSearchService.delete(mine, othersId);
		assertThat(keywords(other)).containsExactly("네이버");
		assertThat(keywords(mine)).hasSize(2);

		recentSearchService.delete(mine, mineId);
		assertThat(keywords(mine)).containsExactly("카카오");
		// 이미 지운 것을 다시, 없는 번호를 — 둘 다 조용히 끝난다.
		recentSearchService.delete(mine, mineId);
		recentSearchService.delete(mine, 999_999L);

		recentSearchService.deleteAll(mine);
		assertThat(keywords(mine)).isEmpty();
		assertThat(keywords(other)).containsExactly("네이버");
	}

	@Test
	@DisplayName("50자를 넘는 검색어는 잘라서 저장한다 — 컬럼이 VARCHAR(50) 이라 넘치면 INSERT 가 실패한다")
	void truncatesLongKeyword() {
		Long userId = newUserId();
		String long60 = "가".repeat(60);

		search(userId, long60);

		assertThat(keywords(userId)).containsExactly("가".repeat(50));
	}

	@Test
	@DisplayName("검색한 적이 없으면 빈 목록이다")
	void emptyList() {
		assertThat(recentSearchService.list(newUserId()).items()).isEmpty();
	}

	// ---- helpers ----

	private void search(Long userId, String keyword) {
		recentSearchService.on(new StockSearchedEvent(userId, keyword));
	}

	private List<String> keywords(Long userId) {
		return recentSearchService.list(userId).items().stream().map(RecentSearchRes.Item::keyword).toList();
	}

	private Long newUserId() {
		return userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg")).getId();
	}
}
