package com.finch.domain.ai.relay;

import org.springframework.http.HttpMethod;

/**
 * apiSpec 10.1 의 경로 매핑 표. 프론트가 부르는 {@code /api/v1/ai/**} 와 AI 서버의 {@code /api/ai/v1/**} 가 짝이다.
 * <p>
 * enum 으로 두는 이유 — 표가 코드 한 곳에 있어야 "중계하는 것 10종" 이 문서와 대조된다 ({@code AiRouteTest}). 컨트롤러의
 * 스프링 매핑은 어노테이션이라 별도로 적을 수밖에 없고, 이 enum 은 <b>AI 쪽 경로</b>를 든다. 경로 변수 이름이 다르다 —
 * 우리는 {@code stockCode}, AI 는 {@code ticker} (contracts C19·C74). 값은 같은 6자리 코드다.
 * <p>
 * {@code POST /api/ai/v1/wiki/theses} 는 AI 가 내부에서 스스로 부르는 경로라 여기 없다 (apiSpec 10.1).
 */
public enum AiRoute {

	STOCK_ANALYSIS(HttpMethod.POST, "/stocks/{ticker}/analysis"),
	CHAT(HttpMethod.POST, "/chat"),
	PORTFOLIO_DIAGNOSIS(HttpMethod.POST, "/portfolio/diagnosis"),
	PORTFOLIO_ATTRIBUTION(HttpMethod.POST, "/portfolio/attribution"),
	ORDER_PREVIEW(HttpMethod.POST, "/orders/preview"),
	BRIEFING(HttpMethod.GET, "/briefing"),
	FEEDBACK(HttpMethod.POST, "/feedback"),
	WIKI(HttpMethod.GET, "/wiki"),
	WIKI_THESIS_UPDATE(HttpMethod.PUT, "/wiki/theses/{ticker}"),
	WIKI_FACT_DELETE(HttpMethod.DELETE, "/wiki/facts/{factId}");

	/** aiApiSpec §1 — 모든 라우터가 이 아래 붙는다. */
	public static final String PREFIX = "/api/ai/v1";

	private final HttpMethod method;
	private final String template;

	AiRoute(HttpMethod method, String template) {
		this.method = method;
		this.template = template;
	}

	public HttpMethod method() {
		return method;
	}

	/** AI 서버 경로 템플릿 (접두사 포함). {@code {ticker}}·{@code {factId}} 는 {@code WebClient.uri(template, vars)} 가 채운다. */
	public String upstreamTemplate() {
		return PREFIX + template;
	}
}
