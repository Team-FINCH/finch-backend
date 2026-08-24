package com.finch.global.apiPayload;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.global.apiPayload.code.GeneralErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 에러 본문의 모양은 프론트와의 계약이다 (apiSpec 1.3).
 * 프론트는 code 로만 분기하고 detail 유무로 화면 표시를 바꾸므로,
 * 필드가 늘거나 빈 값이 섞여 나가면 파싱 단계에서 조용히 깨진다.
 */
class ErrorResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("detail 과 requestId 가 없으면 두 필드 모두 본문에서 빠진다")
	void omitsAbsentFields() throws Exception {
		String json = objectMapper.writeValueAsString(ErrorResponse.of(GeneralErrorCode.RESOURCE_NOT_FOUND));

		assertThat(json).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
		assertThat(json).contains("\"message\"");
		assertThat(json).doesNotContain("detail");
		assertThat(json).doesNotContain("requestId");
	}

	@Test
	@DisplayName("백엔드 자체 에러는 detail 만 담고 requestId 는 담지 않는다")
	void backendErrorHasNoRequestId() throws Exception {
		ErrorResponse response = ErrorResponse.of(GeneralErrorCode.INVALID_REQUEST,
			Map.of("quantity", "1 이상이어야 합니다"));

		String json = objectMapper.writeValueAsString(response);

		assertThat(json).contains("\"detail\"");
		assertThat(json).doesNotContain("requestId");
	}

	@Test
	@DisplayName("AI 중계 에러는 AI 가 발행한 code 와 requestId 를 그대로 싣는다")
	void aiRelayErrorKeepsRequestId() throws Exception {
		ErrorResponse response = ErrorResponse.ofAiRelay("INSUFFICIENT_DATA", "분석에 필요한 데이터가 부족합니다",
			null, "req-abc-123");

		String json = objectMapper.writeValueAsString(response);

		assertThat(json).contains("\"code\":\"INSUFFICIENT_DATA\"");
		assertThat(json).contains("\"requestId\":\"req-abc-123\"");
		assertThat(json).doesNotContain("detail");
	}

	@Test
	@DisplayName("code 가 본문의 첫 필드다")
	void codeComesFirst() throws Exception {
		String json = objectMapper.writeValueAsString(ErrorResponse.of(GeneralErrorCode.INTERNAL_ERROR));

		assertThat(json).startsWith("{\"code\":");
	}
}
