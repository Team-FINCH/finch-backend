package com.finch.global.security;

import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.global.apiPayload.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code /internal/v1/**}(AI 서버 전용, apiSpec 9장)의 인증. {@code X-Internal-Token} 이 설정값과 같아야 통과한다.
 * <p>
 * JWT 가 아니라 <b>서비스 간 공유 비밀값</b>이다 (aiApiSpec §4 의 방식을 방향만 뒤집은 것). 호출자가 사람이 아니라 AI 서버라
 * 로그인·토큰 만료 개념이 없고, 값 하나를 양쪽 환경변수에 같이 두는 것으로 충분하다. 사용자 식별은 인증이 아니라 {@code X-User-Id}
 * 헤더이고 그 값은 컨트롤러가 읽는다 — 이 필터는 "부른 쪽이 우리 AI 서버인가" 만 본다.
 * <p>
 * <b>{@link MessageDigest#isEqual} 로 비교한다.</b> {@code String.equals} 는 앞에서부터 다른 자리를 찾으면 멈춰서 걸린 시간이
 * 토큰을 한 글자씩 알려준다. AI 쪽도 같은 이유로 {@code hmac.compare_digest} 를 쓴다.
 * <p>
 * 누락·불일치는 둘 다 {@code 401 AUTH_INVALID_TOKEN} 이다 (apiSpec 11.2). 무엇이 틀렸는지 구분해 주지 않는다. <b>설정값이 비어
 * 있으면 전부 401 이다</b> — 운영에서 비워 두는 것은 설정 사고이고, 그때 열어 두면 인증이 없는 것과 같다 (aiApiSpec §4 와 같은 판단).
 * <p>
 * 필터에서 던진 예외는 {@code GlobalExceptionHandler} 를 타지 않으므로 본문을 직접 쓴다 ({@code IdempotencyFilter} 와 같다).
 */
@Slf4j
public class InternalTokenFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Internal-Token";

	private final byte[] expected;
	private final ObjectMapper objectMapper;

	public InternalTokenFilter(String expectedToken, ObjectMapper objectMapper) {
		this.expected = expectedToken == null ? new byte[0] : expectedToken.getBytes(StandardCharsets.UTF_8);
		this.objectMapper = objectMapper;
		if (this.expected.length == 0) {
			log.warn("finch.internal.token 이 비어 있다 — /internal/v1 은 전부 401 이다");
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String presented = request.getHeader(HEADER);
		if (expected.length == 0 || presented == null
			|| !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
			reject(response);
			return;
		}
		chain.doFilter(request, response);
	}

	private void reject(HttpServletResponse response) throws IOException {
		AuthErrorCode code = AuthErrorCode.AUTH_INVALID_TOKEN;
		response.setStatus(code.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code));
	}
}
