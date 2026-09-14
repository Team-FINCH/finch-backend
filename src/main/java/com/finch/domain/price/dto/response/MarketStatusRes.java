package com.finch.domain.price.dto.response;

import com.finch.global.util.KstTime;
import com.finch.global.util.MarketClock;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * `GET /market/status` 응답 (apiSpec 5.8). 프론트가 "지금 시세를 물어야 하나" 와 "주문 버튼을 열어야 하나" 를 서버 시계로 판단하게
 * 한다 — 장 시간표를 프론트에 박으면 애프터마켓 도입 같은 변경 때 두 곳을 고쳐야 한다.
 *
 * @param open         주문 접수 가능 (정규장·애프터마켓, 또는 always-open). {@code ORDER_MARKET_CLOSED} 와 같은 판정이다.
 * @param quotesLive   시세가 살아 움직이는 중 (정규장·애프터마켓). false 면 폴링해도 같은 값이 온다 — 프론트가 폴링을 멈추는 신호.
 *                     지금은 {@code open} 과 같은 값이다. 둘을 따로 두는 이유 — 애프터마켓 주문을 다시 닫게 되면 이 둘이 갈린다.
 * @param session      {@code REGULAR} · {@code AFTER} · {@code CLOSED}.
 * @param nextChangeAt 세션이 다음에 바뀌는 시각(KST). 이때 다시 물으면 된다. always-open 이면 null.
 */
public record MarketStatusRes(boolean open, boolean quotesLive, MarketClock.Session session, OffsetDateTime nextChangeAt) {

	public static MarketStatusRes of(boolean open, MarketClock.Session session, Instant nextChangeAt) {
		return new MarketStatusRes(open, session != MarketClock.Session.CLOSED, session,
			nextChangeAt == null ? null : KstTime.toResponse(nextChangeAt));
	}
}
