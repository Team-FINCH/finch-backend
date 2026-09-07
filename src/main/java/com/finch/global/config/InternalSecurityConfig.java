package com.finch.global.config;

import com.finch.global.security.InternalTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code /internal/v1/**} 전용 보안 체인. <b>{@code SecurityConfig} 의 {@code PUBLIC_PATHS} 에 넣지 않는다.</b>
 * <p>
 * 그 목록은 "인증 없이 연다" 는 뜻이라 거기 넣으면 컨트롤러가 생기는 순간 인증 없는 내부 API 가 조용히 공개된다 —
 * {@code SecurityConfig} 주석이 그 경고를 남겨 두었다. 이 경로의 인증은 JWT 가 아니라 {@code X-Internal-Token} 이므로 체인
 * 자체를 따로 둔다. {@code @Order(0)} 이라 기본 체인(순서 없음 = 가장 낮음)보다 먼저 매칭되고, {@code securityMatcher} 가 이 경로만
 * 잡으므로 다른 경로에는 아무 영향이 없다.
 * <p>
 * 이 체인에는 JWT 필터가 없다. 그래서 <b>사용자 JWT 만으로는 401 이다</b> (apiSpec 11.2 "사용자 JWT 인증은 적용되지 않는다").
 * 토큰 검사가 필터에서 끝나므로 인가 규칙은 {@code permitAll} 이다 — 필터가 통과시킨 요청만 여기 닿는다.
 * <p>
 * 외부 노출 차단은 이중이다 — nginx 가 {@code /internal} 을 라우팅하지 않고(인프라), 라우팅돼도 이 필터가 막는다.
 */
@Configuration
public class InternalSecurityConfig {

	@Bean
	@Order(0)
	SecurityFilterChain internalFilterChain(HttpSecurity http, FinchProperties properties, ObjectMapper objectMapper)
		throws Exception {
		return http
			.securityMatcher("/internal/v1/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.addFilterBefore(new InternalTokenFilter(properties.internal().token(), objectMapper),
				AuthorizationFilter.class)
			.build();
	}
}
