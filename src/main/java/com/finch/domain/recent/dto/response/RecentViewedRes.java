package com.finch.domain.recent.dto.response;

import com.finch.domain.recent.repository.RecentViewedRow;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.util.KstTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * `GET /stocks/recent` 응답 (apiSpec 6.1). 최신순이고 최대 30건이다. 본 적이 없으면 빈 {@code items} 이지 에러가 아니다.
 * <p>
 * 시세 둘은 캐시 미스면 null 이다 (apiSpec 5.4 셋째 행). 등락 <b>금액</b>이 없는 것은 명세 그대로다 — 이 화면은 종목명과 등락률만
 * 보여준다 (featureSpec 5장).
 */
public record RecentViewedRes(List<Item> items) {

	public record Item(String stockCode, String stockName, Long currentPrice, BigDecimal changeRate,
		OffsetDateTime viewedAt) {

		public static Item of(RecentViewedRow row, PriceSnapshot price) {
			return new Item(row.getStockCode(), row.getStockName(), price.currentPrice(), price.changeRate(),
				KstTime.toResponse(row.getViewedAt()));
		}
	}
}
