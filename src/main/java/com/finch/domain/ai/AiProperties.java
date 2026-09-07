package com.finch.domain.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 중계 설정 ({@code finch.ai}). 도메인 패키지에 두는 이유는 {@code AccountProperties} 와 같다.
 *
 * @param baseUrl             AI 서버 주소. 배포는 compose 서비스 이름({@code http://ai:8000}), 로컬은 {@code http://localhost:8000}.
 *                            경로 접두사 {@code /api/ai/v1} 은 여기 넣지 않고 {@code AiRoute} 가 붙인다.
 * @param internalToken       백엔드 → AI 방향의 {@code X-Internal-Token}. AI 쪽 {@code BACKEND_SERVICE_TOKEN} 과 같은 값이어야 한다.
 *                            AI → 백엔드 방향({@code finch.internal.token})과 별도 변수다 — 한쪽이 새면 한쪽만 바꾼다. 비밀값이라 기본값이 없다.
 * @param timeout             응답 대기 제한. <b>AI 의 LLM 타임아웃보다 길게</b> 둔다 — AI 가 먼저 {@code 504 LLM_TIMEOUT} 을 돌려주게
 *                            하기 위해서다. 우리가 먼저 끊으면 AI 는 이미 답을 만들고 있는데 프론트는 {@code AI_UPSTREAM_TIMEOUT} 을
 *                            받아 재시도하고, 호출량만 는다. 90초는 가정값이고 AI 파트 확인 후 조정한다 (backend_story §6).
 * @param rateLimitRetryAfter AI 가 429 에 {@code Retry-After} 를 주지 않았을 때 503 에 싣는 기본값 (apiSpec 10.4, 기본 5초).
 */
@ConfigurationProperties("finch.ai")
public record AiProperties(
	@DefaultValue("http://localhost:8000") String baseUrl,
	String internalToken,
	@DefaultValue("90s") Duration timeout,
	@DefaultValue("5s") Duration rateLimitRetryAfter
) {
}
