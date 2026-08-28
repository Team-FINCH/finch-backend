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
 * 이 코드는 백엔드 자체 에러이므로 requestId 가 없다 — AI 서버가 응답하지 않았으니 피드백으로
 * 찾을 원본 응답도 없다. CustomException 으로 던져 ErrorResponse.of 경로를 탄다.
 */
@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements BaseErrorCode {

	AI_UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI 서비스에 연결할 수 없어요. 잠시 후 다시 시도해 주세요"),
	AI_UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 응답이 지연되어 중단했어요. 다시 시도해 주세요");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
