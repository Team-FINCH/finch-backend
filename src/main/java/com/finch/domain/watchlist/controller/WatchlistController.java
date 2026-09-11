package com.finch.domain.watchlist.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.finch.domain.watchlist.dto.request.WatchlistCreateReq;
import com.finch.domain.watchlist.dto.request.WatchlistSort;
import com.finch.domain.watchlist.dto.response.WatchlistRes;
import com.finch.domain.watchlist.service.WatchlistService;
import com.finch.global.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관심 종목 API (apiSpec §6.3). 계정 기준이고 계좌 식별자를 받지 않는다 (apiSpec 1.6).
 * <p>
 * 고유 에러 코드는 등록에만 있다 — {@code STOCK_NOT_FOUND}(404) · {@code WATCHLIST_ALREADY_EXISTS}(409) ·
 * {@code WATCHLIST_LIMIT_EXCEEDED}(409). 목록의 {@code sort} 가 열거값 밖이면 공통 계층이 {@code INVALID_REQUEST} 로 답하고,
 * 해제는 대상이 없어도 204 다.
 */
@RestController
@RequestMapping("/api/v1/watchlist")
@RequiredArgsConstructor
@Tag(name = "관심 종목", description = "관심 종목 조회와 관리")
public class WatchlistController {

	private final WatchlistService watchlistService;

	@GetMapping
	public WatchlistRes list(@LoginUser long userId,
		@RequestParam(defaultValue = "REGISTERED") WatchlistSort sort) {
		return watchlistService.list(userId, sort);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public void add(@LoginUser long userId, @Valid @RequestBody WatchlistCreateReq request) {
		watchlistService.add(userId, request.stockCode());
	}

	/** 토글 해제라 담긴 적 없어도 204 다 — 프론트가 상태를 확인하고 부르지 않아도 된다. */
	@DeleteMapping("/{stockCode}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@LoginUser long userId, @PathVariable String stockCode) {
		watchlistService.remove(userId, stockCode);
	}
}
