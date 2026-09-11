package com.finch.domain.price.dto.response;

import com.finch.domain.price.service.IndexQueryService.IndexSnapshot;
import com.finch.global.util.KstTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * `GET /market/indices` 응답 (apiSpec 5.7). {@code items} 는 언제나 KOSPI → KOSDAQ 두 개이고, 값을 모르는 지수는
 * 셋 다 null 에 {@code stale: true} 다 — {@code PricesRes} 의 "값 없음" 과 같은 모양이다.
 */
public record MarketIndicesRes(List<Item> items) {

	public static MarketIndicesRes of(List<IndexSnapshot> snapshots) {
		return new MarketIndicesRes(snapshots.stream().map(Item::of).toList());
	}

	public record Item(String indexCode, BigDecimal currentValue, BigDecimal changeValue, BigDecimal changeRate,
		OffsetDateTime asOf, boolean stale) {

		public static Item of(IndexSnapshot snapshot) {
			return new Item(snapshot.index().name(), snapshot.currentValue(), snapshot.changeValue(),
				snapshot.changeRate(), snapshot.asOf() == null ? null : KstTime.toResponse(snapshot.asOf()),
				snapshot.stale());
		}
	}
}
