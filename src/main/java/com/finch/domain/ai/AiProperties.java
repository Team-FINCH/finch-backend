package com.finch.domain.ai;

import com.finch.domain.ai.relay.AiRoute;
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
 * @param timeout             LLM 을 기다리는 경로의 응답 대기 제한. <b>AI 의 자체 타임아웃보다 길고 Cloudflare 보다 짧아야</b> 한다 —
 *                            아래 {@link #timeoutFor} 에 세 값이 겹치는 순서를 적어 두었다.
 * @param quickTimeout        LLM 을 기다리지 않는 경로({@link AiRoute#quick()})의 대기 제한. 채팅 작업 생성·조회 둘이고, 하나는
 *                            작업을 적고 202 로 곧장 돌아오며 하나는 테이블을 한 번 읽는다. 여기에 분(分) 단위를 주면 AI 가 멈췄을 때
 *                            2초마다 오는 폴링이 전부 그만큼 서블릿 스레드에 쌓인다.
 */
@ConfigurationProperties("finch.ai")
public record AiProperties(
	@DefaultValue("http://localhost:8000") String baseUrl,
	String internalToken,
	@DefaultValue("60s") Duration timeout,
	@DefaultValue("10s") Duration quickTimeout
) {

	/**
	 * 이 경로를 얼마나 기다릴지.
	 * <p>
	 * <b>타임아웃이 넷 겹쳐 있고 안쪽이 먼저 터져야 한다</b> (이슈 #87·#90).
	 * AI 의 동기 {@code POST /chat} 55초 → 우리 {@code timeout} 60초 → Cloudflare 약 100초 순이다.
	 * 우리가 55초보다 먼저 끊으면 AI 가 만들어 둔 답을 우리가 버리는 것이고(그 호출의 비용은 이미 나갔다),
	 * 100초를 넘기면 Cloudflare 가 먼저 끊어 프론트는 우리 JSON 봉투가 아니라 <b>HTML 504</b> 를 받는다 — #87 이 그것이었다.
	 * 그래서 이 값은 60초 언저리에서만 움직일 수 있고, 위아래 두 값 중 하나가 바뀌면 함께 바뀐다.
	 */
	public Duration timeoutFor(AiRoute route) {
		return route.quick() ? quickTimeout : timeout;
	}
}
