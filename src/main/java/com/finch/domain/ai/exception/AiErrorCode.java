package com.finch.domain.ai.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * apiSpec 11장 "AI 중계 (백엔드 발행분)" 목록. 백엔드가 AI 서버에 **도달하지 못한** 경우만 이 코드를 낸다.
 * <p>
 * AI 서버가 응답한 에러(INSUFFICIENT_DATA, GUARDRAIL_BLOCKED 등)는 여기 두지 않는다 —
 * 그쪽은 AiRelayException 으로 code·status 를 그대로 통과시킨다 (apiSpec 10.4).
 * 두 종류를 가르는 것이 프론트가 "AI 위젯만 접고 시세·주문은 살리는" 에러 경계의 전제다.
 * <p>
 * 도달 실패분(연결 실패·타임아웃)은 requestId 가 없다 — AI 서버가 응답하지 않았으니 피드백으로 찾을 원본 응답도 없다.
 * CustomException 으로 던져 ErrorResponse.of 경로를 탄다. 재포장분(401·403·429)은 AI 가 응답은 했으므로 requestId 가 있으면
 * 보존한다 (contracts C70) — 그쪽은 이 enum 의 code·message 를 빌려 AiRelayException 으로 던진다.
 */
@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements BaseErrorCode {

	/** 연결 실패·비정상 응답, 그리고 upstream 401·403 의 재포장분 (detail.reason=upstream_auth). apiSpec 10.4. */
	AI_UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI 서비스에 연결할 수 없어요. 잠시 후 다시 시도해 주세요"),
	/**
	 * upstream 429. <b>상태는 429 그대로다</b> — AI 의 한도는 {@code (user_id, endpoint)} 단위라 "이 사용자가 많이 보냈다" 가
	 * 사실이다 (v0.8.6). code 만 바꿔 AI 다리에서 온 한도임을 표시한다. 이 문구는 AI 가 message 를 주지 않았을 때만 쓰는
	 * 폴백이다 — 실제로는 AI 의 문구가 내려간다 (apiSpec 10.4).
	 */
	AI_UPSTREAM_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI 요청 한도에 도달했어요"),
	AI_UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 응답이 지연되어 중단했어요. 다시 시도해 주세요");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
