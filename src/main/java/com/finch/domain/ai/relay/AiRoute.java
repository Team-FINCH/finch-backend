package com.finch.domain.ai.relay;

import org.springframework.http.HttpMethod;

/**
 * apiSpec 10.1 의 경로 매핑 표. 프론트가 부르는 {@code /api/v1/ai/**} 와 AI 서버의 {@code /api/ai/v1/**} 가 짝이다.
 * <p>
 * enum 으로 두는 이유 — 표가 코드 한 곳에 있어야 "중계하는 것 15종" 이 문서와 대조된다 ({@code AiRouteTest}). 컨트롤러의
 * 스프링 매핑은 어노테이션이라 별도로 적을 수밖에 없고, 이 enum 은 <b>AI 쪽 경로</b>를 든다. 경로 변수 이름이 다르다 —
 * 우리는 {@code stockCode}, AI 는 {@code ticker} (contracts C19·C74). 값은 같은 6자리 코드다.
 * <p>
 * {@code POST /wiki/theses} 는 v0.8.8 부터 중계한다 (apiSpec 10.1, 이슈 #56). 그 전에는 AI 가 대화 안에서 스스로 부르는
 * 경로라 여기 없었는데, 사용자가 위키 탭·알림함에서 매수 이유를 처음 적는 입구가 필요해졌다 — {@code PUT} 은 논지가 없으면 거부한다.
 * <p>
 * 대화 이력 조회와 위키 확정은 v0.8.18 에서 들어왔다 (이슈 #79). AI 쪽 경로 변수는 {@code conversation_id}·{@code fact_id} 지만
 * 템플릿의 변수 이름은 우리 쪽이 채우는 자리일 뿐이라 {@code factId} 처럼 우리 이름으로 둔다.
 * <p>
 * 채팅 비동기 작업 2종은 v0.8.19 에서 들어왔다 (이슈 #84·#90, AI api-spec §4.2). 이 둘만 {@link #quick()} 이다 —
 * 생성은 작업을 적고 202 로 곧장 돌아오고 조회는 테이블을 한 번 읽을 뿐이라, LLM 을 기다리는 나머지와 대기 시간의
 * 성질이 다르다. 그 구분을 {@code AiRelayService} 가 타임아웃 선택에 쓴다.
 */
public enum AiRoute {

	STOCK_ANALYSIS(HttpMethod.POST, "/stocks/{ticker}/analysis"),
	CHAT(HttpMethod.POST, "/chat"),
	CHAT_JOB_CREATE(HttpMethod.POST, "/chat/jobs", true),
	CHAT_JOB_STATUS(HttpMethod.GET, "/chat/jobs/{jobId}", true),
	CHAT_CONVERSATION_MESSAGES(HttpMethod.GET, "/chat/conversations/{conversationId}/messages"),
	PORTFOLIO_DIAGNOSIS(HttpMethod.POST, "/portfolio/diagnosis"),
	PORTFOLIO_ATTRIBUTION(HttpMethod.POST, "/portfolio/attribution"),
	ORDER_PREVIEW(HttpMethod.POST, "/orders/preview"),
	BRIEFING(HttpMethod.GET, "/briefing"),
	FEEDBACK(HttpMethod.POST, "/feedback"),
	WIKI(HttpMethod.GET, "/wiki"),
	WIKI_THESIS_CREATE(HttpMethod.POST, "/wiki/theses"),
	WIKI_THESIS_UPDATE(HttpMethod.PUT, "/wiki/theses/{ticker}"),
	WIKI_FACT_DELETE(HttpMethod.DELETE, "/wiki/facts/{factId}"),
	WIKI_FACT_CONFIRM(HttpMethod.POST, "/wiki/facts/{factId}/confirm");

	/** aiApiSpec §1 — 모든 라우터가 이 아래 붙는다. */
	public static final String PREFIX = "/api/ai/v1";

	private final HttpMethod method;
	private final String template;
	private final boolean quick;

	AiRoute(HttpMethod method, String template) {
		this(method, template, false);
	}

	AiRoute(HttpMethod method, String template, boolean quick) {
		this.method = method;
		this.template = template;
		this.quick = quick;
	}

	public HttpMethod method() {
		return method;
	}

	/**
	 * LLM 을 기다리지 않는 경로인가. 채팅 작업 생성·조회 둘뿐이다 (AI api-spec §4.2).
	 * <p>
	 * 기본값이 {@code false} 인 것이 중요하다 — 새 경로를 추가하면서 이 값을 빠뜨리면 <b>넉넉한 쪽</b>으로 붙는다.
	 * 반대로 두면 빠뜨린 경로가 LLM 응답을 기다리다 우리 쪽에서 먼저 끊긴다.
	 */
	public boolean quick() {
		return quick;
	}

	/**
	 * AI 서버 경로 템플릿 (접두사 포함). {@code {ticker}}·{@code {factId}}·{@code {conversationId}}·{@code {jobId}} 는
	 * {@code WebClient.uri(template, vars)} 가 채운다.
	 */
	public String upstreamTemplate() {
		return PREFIX + template;
	}
}
