package com.finch.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.deposit.exception.DepositErrorCode;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스프링이 컨트롤러 앞에서 던지는 표준 예외까지 apiSpec 1.3 형식·11장 코드로 내려가는지 확인한다.
 * 핸들러가 빠지면 그 예외는 Exception 핸들러로 떨어져 405·415 가 전부 500 INTERNAL_ERROR 가 되고,
 * 프론트는 code 로만 분기하므로(apiSpec 1.3) "서버 장애" 로 오인한다.
 * <p>
 * 스텁 컨트롤러를 붙인 슬라이스 테스트다. Docker(Testcontainers) 없이 돈다.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.StubController.class)
@Import(GlobalExceptionHandlerTest.StubController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("CustomException 은 에러 코드의 상태·code·detail 로 내려간다")
	void customException() throws Exception {
		mockMvc.perform(get("/stub/custom"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DEPOSIT_LIMIT_EXCEEDED"))
			.andExpect(jsonPath("$.detail.remainingAmount").value(3000000))
			.andExpect(jsonPath("$.requestId").doesNotExist());
	}

	@Test
	@DisplayName("AiRelayException 은 AI 가 정한 상태·code 와 requestId 를 그대로 통과시킨다")
	void aiRelayException() throws Exception {
		mockMvc.perform(get("/stub/relay"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("GUARDRAIL_BLOCKED"))
			.andExpect(jsonPath("$.requestId").value("req-1"));
	}

	@Test
	@DisplayName("본문 검증 실패는 400 INVALID_REQUEST 와 {필드: 사유} detail 이다")
	void bodyValidation() throws Exception {
		mockMvc.perform(post("/stub/body").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.quantity").isString());
	}

	@Test
	@DisplayName("허용되지 않은 메서드는 405 METHOD_NOT_ALLOWED 와 Allow 헤더다")
	void methodNotAllowed() throws Exception {
		mockMvc.perform(get("/stub/body"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(header().string("Allow", "POST"))
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	@DisplayName("JSON 이 아닌 Content-Type 은 415 UNSUPPORTED_MEDIA_TYPE 이다")
	void unsupportedMediaType() throws Exception {
		mockMvc.perform(post("/stub/body").contentType(MediaType.TEXT_PLAIN).content("quantity=1"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	@DisplayName("매핑되지 않은 경로는 404 RESOURCE_NOT_FOUND 다")
	void unknownPath() throws Exception {
		mockMvc.perform(get("/stub/nope"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("깨진 JSON 본문은 400 INVALID_REQUEST 이고 파서 메시지가 본문에 새지 않는다")
	void unreadableBody() throws Exception {
		mockMvc.perform(post("/stub/body").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value(GeneralErrorCode.INVALID_REQUEST.getMessage()))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	@DisplayName("필수 파라미터 누락은 400 INVALID_REQUEST 와 {이름: 사유} detail 이다")
	void missingParameter() throws Exception {
		mockMvc.perform(get("/stub/param"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.size").isString());
	}

	@Test
	@DisplayName("파라미터 타입 불일치는 400 INVALID_REQUEST 와 {이름: 사유} detail 이다")
	void parameterTypeMismatch() throws Exception {
		mockMvc.perform(get("/stub/param").param("size", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.size").isString());
	}

	@Test
	@DisplayName("파라미터 제약 위반은 본문 검증과 같은 400 INVALID_REQUEST 와 {이름: 사유} detail 이다")
	void parameterConstraint() throws Exception {
		mockMvc.perform(get("/stub/param").param("size", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.size").isString());
	}

	@Test
	@DisplayName("처리되지 않은 예외는 500 INTERNAL_ERROR 이고 원본 메시지가 본문에 새지 않는다")
	void unexpected() throws Exception {
		mockMvc.perform(get("/stub/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value(GeneralErrorCode.INTERNAL_ERROR.getMessage()))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	record StubReq(@Min(1) long quantity) {
	}

	@RestController
	@RequestMapping("/stub")
	static class StubController {

		@PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
		Map<String, String> body(@RequestBody @Valid StubReq req) {
			return Map.of("ok", "true");
		}

		@GetMapping("/param")
		Map<String, Integer> param(@RequestParam @Min(1) int size) {
			return Map.of("size", size);
		}

		@GetMapping("/custom")
		void custom() {
			throw new CustomException(DepositErrorCode.DEPOSIT_LIMIT_EXCEEDED, Map.of("remainingAmount", 3000000));
		}

		@GetMapping("/relay")
		void relay() {
			throw new AiRelayException(HttpStatus.UNPROCESSABLE_CONTENT, "GUARDRAIL_BLOCKED",
				"답변할 수 없는 요청입니다", null, "req-1");
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("DB 비밀번호가 틀렸습니다 (내부 정보)");
		}
	}
}
