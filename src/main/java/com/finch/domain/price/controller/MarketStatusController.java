package com.finch.domain.price.controller;

import com.finch.domain.price.dto.response.MarketStatusRes;
import com.finch.global.util.MarketClock;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시장 상태 조회 (apiSpec 5.8). 파라미터도 고유 에러 코드도 없다. 판정은 전부 {@link MarketClock} 이고 여기서는 옮겨 담기만 한다 —
 * 주문 접수({@code OrderValidator})와 stale 판정({@code PriceQueryService})이 같은 시계를 보므로, 프론트가 이 응답으로 폴링을 멈추면
 * 서버가 "장 밖" 으로 보는 시간과 정확히 일치한다.
 */
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
@Tag(name = "시장", description = "KOSPI·KOSDAQ 시장 지수")
public class MarketStatusController {

	private final MarketClock marketClock;

	@GetMapping("/status")
	public MarketStatusRes status() {
		return MarketStatusRes.of(marketClock.isOpen(), marketClock.sessionNow(), marketClock.nextChangeAt());
	}
}
