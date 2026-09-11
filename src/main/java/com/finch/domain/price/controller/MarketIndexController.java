package com.finch.domain.price.controller;

import com.finch.domain.price.dto.response.MarketIndicesRes;
import com.finch.domain.price.service.IndexQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시장 지수 조회 (apiSpec 5.7). 홈 상단 지수 자리의 값 출처다. 파라미터가 없고 고유 에러 코드도 없다 —
 * 지수를 몰라도 에러가 아니라 그 항목만 "값 없음" 이다.
 */
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketIndexController {

	private final IndexQueryService indexQueryService;

	@GetMapping("/indices")
	public MarketIndicesRes indices() {
		return MarketIndicesRes.of(indexQueryService.latestAll());
	}
}
