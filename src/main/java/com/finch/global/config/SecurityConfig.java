package com.finch.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	/**
	 * 이 빈이 없으면 Spring Security 기본 설정이 전 경로를 인증 대상으로 잡는다.
	 * 그러면 쿠버네티스 readinessProbe 가 /actuator/health/readiness 에서 401 을 받아
	 * Pod 가 Ready 에 도달하지 못하고, helm upgrade --atomic 이 실패로 판단해 롤백한다.
	 * 애플리케이션 로그에는 아무 에러도 남지 않으므로 원인을 찾기 어렵다.
	 * <p>
	 * 지금은 로그인이 없어 전 경로를 연다. 인증 수단이 없는 상태에서 authenticated() 를 걸면
	 * 새로 만드는 엔드포인트가 전부 401 이 되어 개발이 막히기 때문이다.
	 * 로그인 구현 시 permitAll() 자리에 apiSpec 2장의 규칙과 JWT 필터가 들어간다.
	 * <p>
	 * CSRF 를 끄는 근거: 인증은 Authorization 헤더로 하고, 쿠키를 쓰는 것은 POST /auth/refresh
	 * 하나뿐인데 그 쿠키가 SameSite=Lax 라 크로스 사이트 요청에는 실리지 않는다 (apiSpec 1.2).
	 * SameSite 를 None 으로 바꾸게 되면 이 판단을 다시 해야 한다.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.build();
	}

}
