package com.finch.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.ai.relay.AiRelayController;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.price.controller.PriceController;
import com.finch.domain.price.dto.response.PricesRes;
import com.finch.domain.stock.dto.response.StockSearchRes;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.service.StockService;
import com.finch.domain.watchlist.service.WatchlistService;
import com.finch.global.exception.CustomException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 종목 범위({@code finch.universe})를 켠 채로 띄워, 범위 밖 종목이 모든 진입점에서 "없는 종목" 이 되는지 본다.
 * 목록은 기본 설정의 30개다. 시드 마스터에 있는 두 종목으로 대조한다 — 삼성전자(005930)는 범위 안, 두산(000150)은 밖.
 * "두산" 검색은 범위 안의 두산에너빌리티(034020)만 남아야 한다.
 */
@SpringBootTest(properties = "finch.universe.enabled=true")
@Import(TestcontainersConfiguration.class)
class StockUniverseGateTest {

	private static final String IN = "005930";
	private static final String OUT = "000150";
	private static final AtomicLong KAKAO_ID = new AtomicLong(9_300_000_000L);

	@Autowired
	private StockUniverse universe;

	@Autowired
	private StockService stockService;

	@Autowired
	private PriceController priceController;

	@Autowired
	private WatchlistService watchlistService;

	@Autowired
	private AiRelayController aiRelayController;

	@Autowired
	private UserRepository userRepository;

	/** 검색·상세가 최근 검색어·최근 본 종목을 기록하므로 실제 사용자가 있어야 한다 — FK 때문이다. */
	private Long userId;

	@BeforeEach
	void createUser() {
		userId = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "범위테스트", "https://img.kakao/u.jpg")).getId();
	}

	@Test
	@DisplayName("설정의 30종목이 범위이고 시드 종목이 안팎으로 갈린다")
	void universeFromConfig() {
		assertThat(universe.restricted()).isTrue();
		assertThat(universe.codes()).hasSize(30);
		assertThat(universe.contains(IN)).isTrue();
		assertThat(universe.contains(OUT)).isFalse();
	}

	@Test
	@DisplayName("검색은 범위 안에서만 찾는다 — '두산' 은 두산에너빌리티만 나오고 두산은 나오지 않는다")
	void searchStaysInsideUniverse() {
		StockSearchRes res = stockService.search(userId, "두산", 10);

		assertThat(res.items()).extracting(StockSearchRes.Item::stockCode).containsExactly("034020");
		assertThat(stockService.search(userId, "삼성전자", 10).items()).extracting(StockSearchRes.Item::stockCode).contains(IN);
	}

	@Test
	@DisplayName("상세·현재가·캔들은 범위 밖이면 STOCK_NOT_FOUND 이고, 주문·관심이 보는 tradable 도 exists=false 다")
	void detailPriceCandlesAndTradableRejectOutside() {
		assertThatThrownBy(() -> stockService.detail(userId, OUT)).isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
		assertThatThrownBy(() -> stockService.price(OUT)).isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
		assertThat(stockService.getTradable(OUT).exists()).isFalse();
		assertThat(stockService.getTradable(IN).exists()).isTrue();
		assertThat(stockService.price(IN).stockCode()).isEqualTo(IN);
	}

	@Test
	@DisplayName("관심 등록은 범위 밖이면 STOCK_NOT_FOUND 다")
	void watchlistAddRejectsOutside() {
		assertThatThrownBy(() -> watchlistService.add(userId, OUT)).isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
	}

	@Test
	@DisplayName("다건 시세는 범위 밖 코드를 items 에서 뺀다 — 전부 밖이면 빈 items 다")
	void bulkPricesDropOutside() {
		PricesRes res = priceController.prices(List.of(IN, OUT));
		assertThat(res.items()).extracting(PricesRes.Item::stockCode).containsExactly(IN);

		assertThat(priceController.prices(List.of(OUT)).items()).isEmpty();
	}

	@Test
	@DisplayName("AI 종목 분석 중계는 범위 밖이면 STOCK_NOT_FOUND 다 — AI 서버를 부르지 않는다")
	void aiAnalysisRejectsOutside() {
		assertThatThrownBy(() -> aiRelayController.analysis(userId, OUT, null)).isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
	}
}
