package com.finch.domain.ai.dto.response;

import com.finch.domain.order.dto.response.TradeSummaryRes;
import com.finch.global.paging.CursorPage;
import java.util.List;

/**
 * `GET /internal/v1/trades` (apiSpec 9.2). 목록 키가 {@code items} 가 아니라 <b>{@code trades}</b> 다 — 공개 API 의 {@code CursorPage}
 * 와 다른 이름이지만 §9.2 가 그렇게 확정했고 AI 의 {@code BackendLedgerSource} 가 그 이름으로 읽는다. 커서 규칙은 같다.
 */
public record InternalTradesRes(List<TradeSummaryRes> trades, String nextCursor, boolean hasNext) {

	public static InternalTradesRes from(CursorPage<TradeSummaryRes> page) {
		return new InternalTradesRes(page.items(), page.nextCursor(), page.hasNext());
	}
}
