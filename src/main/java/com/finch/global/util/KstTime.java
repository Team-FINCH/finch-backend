package com.finch.global.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 응답에 나가는 시각의 표기를 한 곳에서 정한다.
 * <p>
 * apiSpec 1.1 은 모든 시각을 <b>KST 오프셋 포함</b> ISO 8601 로 정했다 ({@code 2026-08-25T10:00:00+09:00}).
 * {@code Instant} 를 그대로 내보내면 Jackson 이 {@code 2026-08-25T01:00:00Z} 로 쓴다 — ISO 8601 이긴
 * 하지만 오프셋이 Z 라 계약과 다르고, 프론트가 문자열을 그대로 보여주는 자리에서 하루가 밀린다.
 * <p>
 * 저장은 UTC({@code TIMESTAMPTZ}), 표기만 KST 다 (backConvention 6장). 도메인마다 각자 {@code ZoneId} 를
 * 적으면 언젠가 한 곳이 빠지고, 그 엔드포인트만 조용히 다른 시각을 보여준다.
 */
public final class KstTime {

	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private KstTime() {
	}

	/** 저장된 시각을 응답용 표기로 바꾼다. */
	public static OffsetDateTime toResponse(Instant instant) {
		return instant.atZone(ZONE).toOffsetDateTime();
	}
}
