package com.finch.domain.portfolio.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.finch.domain.portfolio.dto.request.PortfolioSort;
import com.finch.domain.portfolio.dto.response.PortfolioRes;
import com.finch.domain.portfolio.service.PortfolioQueryService;
import com.finch.global.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보유 종목 API (apiSpec §8.1). 계정 기준이고 계좌 식별자를 받지 않는다 (apiSpec 1.6).
 * <p>
 * 고유 에러 코드가 없다. 보유가 없으면 빈 배열이고 그것은 에러가 아니다 — 신규 사용자의 정상 상태다.
 * {@code sort} 가 열거값 밖이면 공통 계층이 {@code INVALID_REQUEST} 로 답한다.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
@RequiredArgsConstructor
@Tag(name = "포트폴리오", description = "보유 종목과 자산 평가")
public class PortfolioController {

	private final PortfolioQueryService portfolioQueryService;

	@GetMapping
	public PortfolioRes list(@LoginUser Long userId,
		@RequestParam(defaultValue = "EVALUATION") PortfolioSort sort) {
		return portfolioQueryService.list(userId, sort);
	}
}
