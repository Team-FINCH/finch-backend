package com.finch.domain.ai.service;

import com.finch.domain.ai.dto.response.InternalPortfolioRes;
import com.finch.domain.ai.dto.response.InternalTradesRes;
import com.finch.domain.auth.service.UserService;
import com.finch.domain.order.service.TradeQueryService;
import com.finch.domain.portfolio.dto.request.PortfolioSort;
import com.finch.domain.portfolio.service.PortfolioQueryService;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 서버가 원장을 읽는 창구 (apiSpec 9장). <b>읽기 전용</b>이다 — AI 는 원장을 쓰지 않는다 (featureSpec 10.1).
 * <p>
 * 이 서비스는 아무것도 계산하지 않는다. portfolio(3층)·order(4층)가 노출한 DTO 를 받아 §9 의 모양으로 옮길 뿐이다. ai 는 5층이라
 * 둘 다 위에서 아래로 부를 수 있다. 사용자 존재는 auth 의 {@code UserService} 에 묻는다.
 * <p>
 * <b>{@code X-User-Id} 의 사용자가 없으면 404 {@code RESOURCE_NOT_FOUND}</b> (apiSpec 11.2). 공개 API 라면 "토큰이 가리키는 사용자가
 * 없다 = 다시 로그인" 이라 401 이지만, 여기 호출자는 AI 서버이고 재로그인이 없다. 헤더 값이 숫자가 아니면 400 이다.
 */
@Service
@RequiredArgsConstructor
public class InternalQueryService {

	private final UserService userService;
	private final PortfolioQueryService portfolioQueryService;
	private final TradeQueryService tradeQueryService;

	public InternalPortfolioRes portfolio(String userIdHeader) {
		long userId = requireUser(userIdHeader);
		return InternalPortfolioRes.from(portfolioQueryService.list(userId, PortfolioSort.EVALUATION));
	}

	public InternalTradesRes trades(String userIdHeader, String cursor, int size) {
		long userId = requireUser(userIdHeader);
		return InternalTradesRes.from(tradeQueryService.listForInternal(userId, cursor, size));
	}

	/** aiApiSpec §4 — AI 는 식별자 앞뒤 공백을 잘라 보낸다. 우리도 같은 관용을 둔다. */
	private long requireUser(String header) {
		long userId;
		try {
			userId = Long.parseLong(header == null ? "" : header.strip());
		} catch (NumberFormatException e) {
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST, Map.of("X-User-Id", "숫자여야 합니다"));
		}
		if (!userService.exists(userId)) {
			throw new CustomException(GeneralErrorCode.RESOURCE_NOT_FOUND);
		}
		return userId;
	}
}
