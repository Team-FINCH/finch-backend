package com.finch.domain.price.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.finch.domain.price.dto.response.PricesRes;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다건 현재가 조회 (apiSpec 5.5). 경로가 {@code /stocks/**} 아래지만 소유는 price 다 — 경로가 소유를 정하지 않는다
 * (backConvention 2.2, {@code RecentController} 와 같은 경우).
 * <p>
 * <b>종목 존재를 검사하지 않는다.</b> 판정은 stock 만 할 수 있고 price(1층)가 stock(1층)을 부르면 같은 층 참조다. 대신 캐시에 없으면
 * "값 없음" 으로 답한다 — 없는 코드는 캐시에 생길 수 없으므로(공급자가 관심 목록만 채우고 그 목록은 실제 조회에서 온다) 결과가 같다.
 * 존재 판정이 필요한 단건 조회({@code /stocks/{stockCode}/price})는 {@code StockController} 가 소유한다.
 * <p>
 * {@code /stocks/prices} 는 리터럴이라 {@code /stocks/{stockCode}} 보다 먼저 잡힌다. 종목코드가 6자리라 {@code prices} 라는 코드도 없다.
 */
@RestController
@RequestMapping("/api/v1/stocks")
@RequiredArgsConstructor
@Tag(name = "시세", description = "관심 종목 실시간 시세")
public class PriceController {

	/** apiSpec 5.5 — 관심 종목 최대 50개와 맞춘 값이다. KIS 실시간 등록 한도와는 무관하다(수집 계층이 흡수한다). */
	private static final int MAX_CODES = 50;

	private final PriceQueryPort priceQueryPort;

	/**
	 * 파라미터 이름은 필드 표기와 같은 계열인 {@code stockCodes} 로 확정돼 있다 (apiSpec 5.5 — {@code codes}·{@code tickers} 아님).
	 * <p>
	 * 검증을 {@code @Size} 가 아니라 손으로 하는 이유 — {@code ?stockCodes=} 처럼 빈 값이 오면 스프링이 빈 문자열 하나짜리
	 * 목록으로 바꿔서 크기가 1 이 된다. 공백을 걷어낸 뒤에 세어야 "빈 값" 과 "한 건" 이 구분된다.
	 */
	@GetMapping("/prices")
	public PricesRes prices(@RequestParam List<String> stockCodes) {
		List<String> codes = normalize(stockCodes);
		Map<String, PriceSnapshot> prices = priceQueryPort.latestAll(codes);
		return new PricesRes(codes.stream()
			.map(code -> PricesRes.Item.of(code, prices.getOrDefault(code, PriceSnapshot.missing())))
			.toList());
	}

	/** 공백 제거 · 빈 값 제거 · 중복 제거. 같은 코드를 두 번 보내도 응답에 두 번 담지 않는다. */
	private static List<String> normalize(List<String> raw) {
		LinkedHashSet<String> codes = new LinkedHashSet<>();
		for (String code : raw) {
			if (code != null && !code.isBlank()) {
				codes.add(code.strip());
			}
		}
		if (codes.isEmpty()) {
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST, Map.of("stockCodes", "종목코드를 하나 이상 보내주세요"));
		}
		if (codes.size() > MAX_CODES) {
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST,
				Map.of("stockCodes", "한 번에 최대 " + MAX_CODES + "건까지 조회할 수 있습니다"));
		}
		return new ArrayList<>(codes);
	}
}
