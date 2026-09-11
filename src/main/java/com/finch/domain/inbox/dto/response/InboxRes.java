package com.finch.domain.inbox.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * `GET /inbox` 응답 (apiSpec 6.4). {@code unreadCount} 는 {@code items} 중 안 읽은 개수이고 헤더 뱃지가 그대로 그린다.
 */
public record InboxRes(int unreadCount, List<Item> items) {

	public static InboxRes of(List<Item> items) {
		return new InboxRes((int) items.stream().filter(Item::unread).count(), items);
	}

	public static InboxRes empty() {
		return new InboxRes(0, List.of());
	}

	/**
	 * 항목 종류 (apiSpec 6.4 표). 셋 다 계약에 있지만 <b>지금 나오는 것은 {@code record} 하나</b>다 — {@code wiki} 는 AI 추측 생성기가
	 * 없고(이슈 #52), {@code news} 는 종목별 소식의 원천이 정해지지 않았다. 세 값 밖은 내보내지 않는다.
	 */
	public enum Kind {
		RECORD, WIKI, NEWS;

		@JsonValue
		public String value() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * @param itemId  불투명 식별자. 프론트는 해석하지 않고 읽음 표시에 돌려보낸다.
	 * @param tradeId {@code record} 를 만든 매수 체결. 논지를 기록할 때 {@code linkedTradeId} 로 넘긴다(apiSpec 10.1). 다른 종류는 null.
	 */
	public record Item(String itemId, Kind kind, String title, String summary, boolean unread, OffsetDateTime createdAt,
		String stockCode, String stockName, Long tradeId) {

		public static Item record(String itemId, String stockCode, String stockName, Long tradeId, Instant boughtAt,
			boolean unread) {
			return new Item(itemId, Kind.RECORD, stockName + ", 왜 담으셨나요?",
				"체결 직후 이유를 적어 두면 AI가 이 기록을 근거로 더 맞는 추천을 해줘요.", unread, KstTime.toResponse(boughtAt),
				stockCode, stockName, tradeId);
		}
	}
}
