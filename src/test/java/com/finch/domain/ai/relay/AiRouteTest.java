package com.finch.domain.ai.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * apiSpec 10.1 의 표 그대로 15종이다. POST /wiki/theses 는 v0.8.8 에서(이슈 #56), 대화 이력·위키 확정은 v0.8.18 에서(이슈 #79),
 * 채팅 작업 2종은 v0.8.19 에서 들어왔다(이슈 #84·#90).
 */
class AiRouteTest {

	@Test
	@DisplayName("중계 대상은 15종이고 전부 /api/ai/v1 아래다")
	void fifteenRoutes() {
		assertThat(AiRoute.values()).hasSize(15);
		assertThat(Stream.of(AiRoute.values()).map(AiRoute::upstreamTemplate))
			.allMatch(p -> p.startsWith("/api/ai/v1/"));
		assertThat(AiRoute.WIKI_THESIS_CREATE.method()).isEqualTo(HttpMethod.POST);
		assertThat(AiRoute.WIKI_THESIS_CREATE.upstreamTemplate()).isEqualTo("/api/ai/v1/wiki/theses");
		assertThat(AiRoute.BRIEFING.method()).isEqualTo(HttpMethod.GET);
		assertThat(AiRoute.WIKI_THESIS_UPDATE.method()).isEqualTo(HttpMethod.PUT);
		assertThat(AiRoute.WIKI_FACT_DELETE.upstreamTemplate()).isEqualTo("/api/ai/v1/wiki/facts/{factId}");
		assertThat(AiRoute.STOCK_ANALYSIS.upstreamTemplate()).isEqualTo("/api/ai/v1/stocks/{ticker}/analysis");
		assertThat(AiRoute.CHAT_CONVERSATION_MESSAGES.method()).isEqualTo(HttpMethod.GET);
		assertThat(AiRoute.CHAT_CONVERSATION_MESSAGES.upstreamTemplate())
			.isEqualTo("/api/ai/v1/chat/conversations/{conversationId}/messages");
		assertThat(AiRoute.WIKI_FACT_CONFIRM.method()).isEqualTo(HttpMethod.POST);
		assertThat(AiRoute.WIKI_FACT_CONFIRM.upstreamTemplate()).isEqualTo("/api/ai/v1/wiki/facts/{factId}/confirm");
		assertThat(AiRoute.CHAT_JOB_CREATE.method()).isEqualTo(HttpMethod.POST);
		assertThat(AiRoute.CHAT_JOB_CREATE.upstreamTemplate()).isEqualTo("/api/ai/v1/chat/jobs");
		assertThat(AiRoute.CHAT_JOB_STATUS.method()).isEqualTo(HttpMethod.GET);
		assertThat(AiRoute.CHAT_JOB_STATUS.upstreamTemplate()).isEqualTo("/api/ai/v1/chat/jobs/{jobId}");
	}

	/**
	 * 짧은 타임아웃이 붙는 경로를 못 박는다. 이 목록이 늘면 <b>LLM 응답을 기다리는 경로가 10초에 끊기게 되므로</b>
	 * 늘리는 쪽이 이 테스트를 함께 고쳐야 한다. 그 마찰이 의도다 ({@code IdempotencyPropertiesTest} 와 같은 이유).
	 */
	@Test
	@DisplayName("LLM 을 기다리지 않는 경로는 채팅 작업 생성·조회 둘뿐이다")
	void onlyChatJobRoutesAreQuick() {
		assertThat(Stream.of(AiRoute.values()).filter(AiRoute::quick))
			.containsExactlyInAnyOrder(AiRoute.CHAT_JOB_CREATE, AiRoute.CHAT_JOB_STATUS);
		// 같은 채팅이라도 동기 /chat 은 LLM 을 끝까지 기다린다 — 여기 섞이면 55초짜리 응답이 10초에 끊긴다.
		assertThat(AiRoute.CHAT.quick()).isFalse();
	}
}
