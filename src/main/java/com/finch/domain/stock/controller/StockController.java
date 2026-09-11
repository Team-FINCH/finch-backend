package com.finch.domain.stock.controller;

import com.finch.domain.stock.dto.request.CandleInterval;
import com.finch.domain.stock.dto.request.CandlePeriod;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.dto.response.StockDetailRes;
import com.finch.domain.stock.dto.response.StockPriceRes;
import com.finch.domain.stock.dto.response.StockSearchRes;
import com.finch.domain.stock.service.StockService;
import com.finch.global.security.LoginUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 종목 API (apiSpec §5.1~§5.3). 셋 다 인증이 필요하다 — 검색도 사용자를 알아야 최근 검색어를 기록한다.
 * <p>
 * 파라미터 검증은 공통 계층이 {@code INVALID_REQUEST} 로 답한다 (apiSpec 11.1·11.2): {@code keyword} 2글자 미만·{@code size} 1~10 밖은
 * 제약 위반, {@code period} 열거값 밖은 {@link CandlePeriod#from}, {@code interval} 열거값 밖은 스프링의 타입 불일치다.
 * 셋 다 {@code {이름: 사유}} 로 같은 모양이 나간다. 이 세 엔드포인트의 고유 코드는 {@code STOCK_NOT_FOUND} 뿐이다.
 * <p>
 * {@code /stocks/search} 는 {@code /stocks/{stockCode}} 보다 구체적인 경로라 먼저 매칭된다. S6 의 {@code /stocks/recent}·
 * {@code /stocks/search/recent}, S7 의 {@code /stocks/prices} 도 같은 이유로 충돌하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/stocks")
@RequiredArgsConstructor
@Tag(name = "종목", description = "종목 검색, 상세, 현재가와 캔들")
public class StockController {

	private static final int SEARCH_DEFAULT_SIZE = 10;
	private static final int SEARCH_MAX_SIZE = 10;

	private final StockService stockService;

	/** 자동완성 (featureSpec 4장 — 2글자 이상, 최대 10건). {@code size} 는 선택이고 기본 10 이다. */
	@GetMapping("/search")
	public StockSearchRes search(@LoginUser long userId,
		@RequestParam @Size(min = 2, message = "2글자 이상 입력해 주세요") String keyword,
		@RequestParam(required = false) @Min(1) @Max(SEARCH_MAX_SIZE) Integer size) {
		return stockService.search(userId, keyword, size == null ? SEARCH_DEFAULT_SIZE : size);
	}

	/** 상세. 이 호출이 곧 "최근 본 종목" 기록이다 (apiSpec 5.2). */
	@GetMapping("/{stockCode}")
	public StockDetailRes detail(@LoginUser long userId, @PathVariable String stockCode) {
		return stockService.detail(userId, stockCode);
	}

	/**
	 * 현재가 단건 (apiSpec 5.4). 폴링 방식일 때 프론트가 부른다.
	 * <p>
	 * <b>이 경로만 stock 이 소유하고 다건({@code /stocks/prices})은 price 가 소유한다.</b> 이유는 {@code StockService.price} 주석에 있다 —
	 * 여기는 없는 종목에 404 를 내야 하고 그 판정은 stock 만 할 수 있다.
	 */
	@GetMapping("/{stockCode}/price")
	public StockPriceRes price(@PathVariable String stockCode) {
		return stockService.price(stockCode);
	}

	/**
	 * 캔들. {@code period} 는 조회 범위(기본 {@code 1M}), {@code interval} 은 봉 하나의 크기(기본 {@code DAY})다.
	 * <p>
	 * 받는 방식이 둘로 갈리는 이유 — {@code period} 는 {@code 1M} 이 자바 식별자가 될 수 없어 문자열로 받아
	 * {@link CandlePeriod#from} 이 읽고, {@code interval} 은 값이 열거값 이름 그대로라 스프링 변환기가 읽는다.
	 * 열거값 밖일 때 나가는 응답은 두 경우가 같다 (클래스 주석).
	 * <p>
	 * <b>{@code interval} 에 기본값이 있어 기존 호출은 그대로 동작한다.</b> 이 파라미터를 붙이기 전에 나가던 요청은
	 * {@code DAY} 로 읽혀 예전과 같은 응답을 받는다 (apiSpec 5.3, 이슈 #37).
	 */
	@GetMapping("/{stockCode}/candles")
	public CandleRes candles(@PathVariable String stockCode, @RequestParam(defaultValue = "1M") String period,
		@RequestParam(defaultValue = "DAY") CandleInterval interval) {
		return stockService.candles(stockCode, CandlePeriod.from(period), interval);
	}
}
