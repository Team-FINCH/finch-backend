package com.finch.global.apiPayload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.finch.global.apiPayload.code.BaseErrorCode;

/**
 * apiSpec 1.3 의 실패 응답 본문.
 * <p>
 * 성공 응답에는 봉투를 씌우지 않는다 — 컨트롤러가 리소스 DTO 를 그대로 반환한다.
 * 프론트는 HTTP 상태로 성공·실패를 가르고 code 문자열로만 실패 종류를 분기하므로
 * isSuccess 같은 성공 여부 필드를 두지 않는다.
 * <p>
 * detail 과 requestId 는 값이 없으면 필드 자체가 응답에서 사라진다.
 */
@JsonPropertyOrder({"code", "message", "detail", "requestId"})
@JsonInclude(JsonInclude.Include.NON_NULL) // null 인 필드는 출력에서 제외
public record ErrorResponse(String code, String message, Object detail, String requestId) {

	public static ErrorResponse of(BaseErrorCode errorCode) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), null, null);
	}

	public static ErrorResponse of(BaseErrorCode errorCode, Object detail) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), detail, null);
	}

	/**
	 * AI 서버가 발행한 에러를 그대로 통과시킬 때 쓴다 (apiSpec 10.4).
	 * code 는 AI 서버가 정한 문자열(INSUFFICIENT_DATA 등)이라 백엔드 enum 에 없다.
	 * requestId 는 POST /ai/feedback 이 원본 응답을 찾는 열쇠이므로 에러에도 보존한다 (10.3).
	 */
	public static ErrorResponse ofAiRelay(String code, String message, Object detail, String requestId) {
		return new ErrorResponse(code, message, detail, requestId);
	}
}
