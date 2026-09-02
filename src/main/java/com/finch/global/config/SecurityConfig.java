package com.finch.global.config;

import com.finch.global.security.JwtAuthenticationEntryPoint;
import com.finch.global.security.JwtAuthenticationFilter;
import com.finch.global.security.JwtProvider;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

	/**
	 * 인증 없이 열어 두는 경로. 늘릴 때는 <b>왜 무인증이어야 하는지</b>를 여기 적는다.
	 * <p>
	 * `/actuator/health/**` — 쿠버네티스 readinessProbe·livenessProbe 가 쓴다. 막으면 Pod 가 401 을
	 * 받아 Ready 에 도달하지 못하고, `helm upgrade --atomic` 이 실패로 판단해 롤백한다.
	 * <b>애플리케이션 로그에는 아무 에러도 남지 않아</b> 원인을 찾기 어렵다.
	 * `/actuator/prometheus` — Prometheus scrape 대상이다. 막으면 에러 없이 빈 스크레이프만 계속된다.
	 * 둘 다 인증 헤더를 붙일 방법이 없는 호출자다. {@code SecurityConfigTest} 가 이 세 경로를 못 박는다.
	 * <p>
	 * 나머지 actuator 엔드포인트(`/actuator/info` 등)는 열지 않는다 — 프로브가 쓰지 않는다.
	 * <p>
	 * ⚠️ {@code /internal/v1/**}(AI 서버 전용, apiSpec 9장)은 <b>일부러 넣지 않았다.</b> 그 경로의 인증은
	 * JWT 가 아니라 {@code X-Internal-Token} 이고(apiSpec 11.2) 그 검사 코드가 아직 없다. 지금 열어 두면
	 * 컨트롤러가 생기는 순간 <b>인증 없는 내부 API</b> 가 조용히 공개된다. 닫아 두면 첫 호출이 401 로
	 * 실패해 구현자에게 즉시 드러난다. 그 엔드포인트를 만드는 사람이 자기 규칙을 여기 추가한다.
	 */
	private static final String[] PUBLIC_PATHS = {
		// apiSpec 1.2 — 인증 불필요 엔드포인트. 로그인 전이라 토큰이 있을 수 없다.
		"/api/v1/auth/kakao",
		// 재발급의 판정 기준은 쿠키뿐이다. Access 가 만료됐을 때 부르는 API 라 무인증이어야 한다.
		"/api/v1/auth/refresh",
		"/actuator/health/**",
		"/actuator/prometheus",
	};

	/**
	 * 이 빈이 없으면 Spring Security 기본 설정이 전 경로를 인증 대상으로 잡는다 (위 PUBLIC_PATHS 주석).
	 * <p>
	 * 인증의 뼈대는 세 조각으로 나뉘어 있고 각자 한 가지만 한다.
	 * <ol>
	 *   <li>{@link JwtAuthenticationFilter} — 헤더를 읽어 사용자를 앉힌다. <b>거부하지 않는다.</b></li>
	 *   <li>{@code AuthorizationFilter}(스프링 기본) — 아래 경로 규칙으로 거부 여부를 정한다.</li>
	 *   <li>{@link JwtAuthenticationEntryPoint} — 401 본문을 쓴다. 쓰는 곳은 여기 하나다.</li>
	 * </ol>
	 * 나눈 이유는 {@code JwtAuthenticationFilter} 주석에 있다 — 필터가 직접 거부하면 무인증 경로가
	 * 무효한 헤더 때문에 깨지고, 예외를 던지면 {@code GlobalExceptionHandler} 를 타지 못해 500 이 나간다.
	 * <p>
	 * CSRF 를 끄는 근거: 인증은 Authorization 헤더로 하고, 쿠키를 쓰는 것은 POST /auth/refresh
	 * 하나뿐인데 그 쿠키가 SameSite=Lax 라 크로스 사이트 요청에는 실리지 않는다 (apiSpec 1.2).
	 * SameSite 를 None 으로 바꾸게 되면 이 판단을 다시 해야 한다.
	 * <p>
	 * <b>CORS 설정을 두지 않는다.</b> 배포는 nginx 가 `/` → 프론트, `/api/` → 백엔드로 붙여 같은
	 * 오리진이고, 로컬은 프론트가 Vite dev proxy 로 같은 오리진을 만든다 — 서버에 로컬 전용 CORS·쿠키
	 * 도메인 설정을 두지 않는 것이 팀 규약이다 (frontConvention 2.3, 프론트 contracts C32).
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, JwtProvider jwtProvider, ObjectMapper objectMapper)
		throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// ERROR 디스패치는 인증 판정에서 뺀다. 인증된 요청이 예외로 끝나면 컨테이너가 /error 로
				// 다시 디스패치하는데, 그때 SecurityContext 는 이미 비어 있어 500 이 401 로 둔갑한다.
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				// 메서드를 함께 지정하지 않는다. POST 만 열면 GET /auth/kakao 가 401 이 되는데
				// 경로가 존재하므로 apiSpec 11.1 대로 405 METHOD_NOT_ALLOWED 여야 한다.
				.requestMatchers(PUBLIC_PATHS).permitAll()
				// 화이트리스트 밖은 전부 인증이다. 새 엔드포인트는 별도 설정 없이 보호된다 —
				// 여는 것이 기본이면 빠뜨린 경로가 조용히 공개되지만, 닫는 것이 기본이면 401 로 드러난다.
				.anyRequest().authenticated())
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper)))
			// UsernamePasswordAuthenticationFilter 자리 앞에 끼운다. 그 필터는 폼 로그인용이라 이 체인에
			// 없지만, addFilterBefore 는 실제 등록 여부가 아니라 등록된 순서표를 보므로 자리는 정해진다.
			// 요구 조건은 "인증 판정(AuthorizationFilter)보다 앞" 하나뿐이고 이 자리가 관례다.
			.addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
			.build();
	}

}
