package com.finch.domain.ai.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** apiSpec 10.1 의 표 그대로 11종이다. POST /wiki/theses 는 v0.8.8 에서 들어왔다(이슈 #56). */
class AiRouteTest {

	@Test
	@DisplayName("중계 대상은 11종이고 전부 /api/ai/v1 아래다")
	void elevenRoutes() {
		assertThat(AiRoute.values()).hasSize(11);
		assertThat(Stream.of(AiRoute.values()).map(AiRoute::upstreamTemplate))
			.allMatch(p -> p.startsWith("/api/ai/v1/"));
		assertThat(AiRoute.WIKI_THESIS_CREATE.method()).isEqualTo(HttpMethod.POST);
		assertThat(AiRoute.WIKI_THESIS_CREATE.upstreamTemplate()).isEqualTo("/api/ai/v1/wiki/theses");
		assertThat(AiRoute.BRIEFING.method()).isEqualTo(HttpMethod.GET);
		assertThat(AiRoute.WIKI_THESIS_UPDATE.method()).isEqualTo(HttpMethod.PUT);
		assertThat(AiRoute.WIKI_FACT_DELETE.upstreamTemplate()).isEqualTo("/api/ai/v1/wiki/facts/{factId}");
		assertThat(AiRoute.STOCK_ANALYSIS.upstreamTemplate()).isEqualTo("/api/ai/v1/stocks/{ticker}/analysis");
	}
}
