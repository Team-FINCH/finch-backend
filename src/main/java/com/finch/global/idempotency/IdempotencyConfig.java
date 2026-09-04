package com.finch.global.idempotency;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 멱등성 필터를 서블릿 필터 체인에 올린다.
 * <p>
 * <b>{@code @Component} 가 아니라 여기서 만드는 이유.</b> {@code @WebMvcTest} 는 컨트롤러만 띄우는
 * 슬라이스지만 {@code jakarta.servlet.Filter} 타입 빈은 함께 스캔한다. 필터에 {@code @Component} 를
 * 붙이면 슬라이스가 그 필터를 만들려 들고, 필터는 {@link IdempotencyStore} 를, 그것은 다시 Redis 를
 * 요구한다 — <b>멱등성과 무관한 컨트롤러 테스트가 Redis 가 없다는 이유로 통째로 죽는다.</b>
 * {@code @Configuration} 은 슬라이스의 스캔 대상이 아니라서 이 자리에 두면 그 일이 생기지 않는다.
 * {@code SecurityConfig} 도 같은 이유로 슬라이스 테스트가 {@code @Import} 로 직접 가져간다.
 * <p>
 * <b>Spring Security 체인에 끼우지 않고 일반 필터 빈으로 두는 이유.</b> 순서를 지정하지 않은 필터 빈은
 * {@code springSecurityFilterChain}(순서 -100) 보다 뒤에 등록되어 그 안쪽에서 돈다. 그 자리가 정확히
 * 우리가 원하는 곳이다 — 인증·인가가 끝나 {@code SecurityContext} 에 사용자가 앉아 있고(멱등성 키가
 * 사용자별로 갈린다) 컨트롤러는 아직 돌지 않았다.
 */
@Configuration
public class IdempotencyConfig {

	@Bean
	IdempotencyFilter idempotencyFilter(IdempotencyStore store, ObjectMapper objectMapper,
		IdempotencyProperties properties) {
		return new IdempotencyFilter(store, objectMapper, properties);
	}
}
