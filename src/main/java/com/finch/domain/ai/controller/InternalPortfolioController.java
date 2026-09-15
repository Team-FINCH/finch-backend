package com.finch.domain.ai.controller;

import com.finch.domain.ai.dto.response.InternalCashFlowsRes;
import com.finch.domain.ai.dto.response.InternalPortfolioRes;
import com.finch.domain.ai.dto.response.InternalTradesRes;
import com.finch.domain.ai.service.InternalQueryService;
import com.finch.global.paging.PageSize;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 서버 내부 연동 API (apiSpec 9장). 경로 접두사가 {@code /api/v1} 이 아니라 {@code /internal/v1} 이다 — 외부에 열리는 API 가
 * 아니고, 인증도 JWT 가 아니라 {@code X-Internal-Token} 이다 ({@code InternalSecurityConfig}). 이 컨트롤러가 닿았다는 것은 그 필터를
 * 통과했다는 뜻이고, 사용자는 {@code @LoginUser} 가 아니라 {@code X-User-Id} 헤더다.
 * <p>
 * 헤더가 없으면 스프링이 {@code MissingRequestHeaderException} 을 던져 500 이 된다 — 그래서 {@code required=false} 로 받고 서비스가
 * 400 으로 판정한다. {@code size} 는 검증 없이 {@code PageSize.forInternal} 이 1~100 으로 깎는다 — 호출자가 AI 서버라 사람이
 * 400 을 보고 고칠 일이 없고, 명세가 "기본 100, 최대 100" 이다.
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalPortfolioController {

	public static final String USER_HEADER = "X-User-Id";

	private final InternalQueryService internalQueryService;

	@GetMapping("/portfolio")
	public InternalPortfolioRes portfolio(@RequestHeader(value = USER_HEADER, required = false) String userId) {
		return internalQueryService.portfolio(userId);
	}

	@GetMapping("/trades")
	public InternalTradesRes trades(@RequestHeader(value = USER_HEADER, required = false) String userId,
		@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		String pageCursor = cursor == null || cursor.isBlank() ? null : cursor;
		return internalQueryService.trades(userId, pageCursor, PageSize.forInternal(size));
	}

	/** 입출금 이력 (apiSpec 9.3). 헤더·커서·{@code size} 처리는 {@link #trades} 와 같다. */
	@GetMapping("/cash-flows")
	public InternalCashFlowsRes cashFlows(@RequestHeader(value = USER_HEADER, required = false) String userId,
		@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		String pageCursor = cursor == null || cursor.isBlank() ? null : cursor;
		return internalQueryService.cashFlows(userId, pageCursor, PageSize.forInternal(size));
	}
}
