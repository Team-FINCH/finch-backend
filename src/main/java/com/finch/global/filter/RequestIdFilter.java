package com.finch.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 식별자를 하나 정해 응답 헤더와 로그에 싣는다 (apiSpec 1.1 요청 추적).
 * <p>
 * 같은 시각에 여러 사용자의 요청이 섞여 들어오면 로그만으로는 어느 줄이 누구 것인지 가를 수 없다.
 * 여기서 정한 값을 MDC 에 두면 이후 어느 계층에서 찍는 로그든 logging.pattern 의 %X{requestId} 로
 * 자동으로 붙는다. 서비스 코드는 이 필터의 존재를 몰라도 된다.
 * <p>
 * 순서를 가장 앞에 두는 이유 — Spring Security 가 401 을 내며 남기는 로그에도 식별자가 붙어야 한다.
 * 응답 헤더를 chain 호출 **전에** 쓰는 이유 — 예외 핸들러가 본문을 다시 쓰더라도 헤더는 남는다.
 * <p>
 * 이 값은 AI 중계 본문의 requestId(apiSpec 10.3, AI 서버가 발급) 와 다른 것이다. 헤더는 백엔드 로그 추적용,
 * 본문 필드는 피드백이 원본 응답을 찾는 열쇠다 (apiSpec 1.3).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	/**
	 * 클라이언트(nginx·프론트)가 먼저 발급한 값은 이어받는다 — 프록시 로그와 같은 값으로 묶인다.
	 * 단, 로그에 그대로 찍히는 값이라 임의 문자열을 받지 않는다. 개행·공백이 섞이면 로그 한 줄이
	 * 여러 줄로 쪼개져 검색이 깨진다(log injection). 형식이 어긋나면 버리고 새로 만든다.
	 */
	private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String requestId = resolve(request.getHeader(HEADER));
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		} finally {
			// 스레드는 풀에서 재사용된다. 지우지 않으면 다음 요청 로그에 이전 요청의 식별자가 붙는다.
			MDC.remove(MDC_KEY);
		}
	}

	private static String resolve(String incoming) {
		if (incoming != null && ACCEPTABLE.matcher(incoming).matches()) {
			return incoming;
		}
		return UUID.randomUUID().toString();
	}
}
