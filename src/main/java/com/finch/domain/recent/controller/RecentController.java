package com.finch.domain.recent.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.finch.domain.recent.dto.response.RecentSearchRes;
import com.finch.domain.recent.dto.response.RecentViewedRes;
import com.finch.domain.recent.service.RecentSearchService;
import com.finch.domain.recent.service.RecentViewedService;
import com.finch.global.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 최근 본 종목·최근 검색어 API (apiSpec §6.1·§6.2). 둘 다 계정 기준이고 등록 엔드포인트가 없다 — 기록은 종목 조회·검색이 발행한
 * 이벤트로 일어난다.
 * <p>
 * <b>경로가 {@code /stocks/**} 아래지만 소유는 recent 다</b> (backConvention 2.2). 경로가 소유를 정하지 않는다.
 * {@code StockController} 도 같은 접두사를 쓰지만 패턴이 겹치지 않는다 — 스프링은 리터럴({@code /stocks/recent})을 변수
 * ({@code /stocks/{stockCode}})보다 먼저 맞춘다. 종목코드가 6자리라 {@code recent} 라는 코드가 생길 일도 없다.
 * <p>
 * <b>DELETE 는 대상이 없어도 204 다</b> (apiSpec 11.2). 이미 지운 것을 다시 지워도, 남의 {@code keywordId} 를 지목해도 같다 —
 * 존재 여부 자체가 정보 노출이다.
 */
@RestController
@RequestMapping("/api/v1/stocks")
@RequiredArgsConstructor
@Tag(name = "최근 기록", description = "최근 본 종목과 최근 검색어")
public class RecentController {

	private final RecentViewedService recentViewedService;
	private final RecentSearchService recentSearchService;

	@GetMapping("/recent")
	public RecentViewedRes recentStocks(@LoginUser long userId) {
		return recentViewedService.list(userId);
	}

	@DeleteMapping("/recent")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void clearRecentStocks(@LoginUser long userId) {
		recentViewedService.deleteAll(userId);
	}

	@DeleteMapping("/recent/{stockCode}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRecentStock(@LoginUser long userId, @PathVariable String stockCode) {
		recentViewedService.delete(userId, stockCode);
	}

	@GetMapping("/search/recent")
	public RecentSearchRes recentKeywords(@LoginUser long userId) {
		return recentSearchService.list(userId);
	}

	@DeleteMapping("/search/recent")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void clearRecentKeywords(@LoginUser long userId) {
		recentSearchService.deleteAll(userId);
	}

	@DeleteMapping("/search/recent/{keywordId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRecentKeyword(@LoginUser long userId, @PathVariable Long keywordId) {
		recentSearchService.delete(userId, keywordId);
	}
}
