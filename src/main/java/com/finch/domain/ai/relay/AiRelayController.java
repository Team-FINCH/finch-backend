package com.finch.domain.ai.relay;

import com.finch.domain.ai.service.WikiThesisService;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.global.exception.CustomException;
import com.finch.global.security.LoginUser;
import com.finch.global.util.StockUniverse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * AI 중계 API 13종 (apiSpec 10.1). 한 컨트롤러에 모아 두는 이유 — 전부 같은 일({@link AiRelayService#relay})을 하고 다른 것은
 * 경로와 메서드뿐이다. {@link AiRoute} 가 AI 쪽 경로를, 여기 어노테이션이 우리 쪽 경로를 든다.
 * <p>
 * 본문은 {@link JsonNode} 로 받는다 — 엔드포인트별 DTO 를 두지 않는다 (apiSpec 10.3 "제네릭 변환"). 검증도 하지 않는다: 요청 형식
 * 오류는 AI 가 {@code 400 INVALID_REQUEST} 로 답하고 그것이 그대로 통과된다 (10.4). 백엔드가 종목 존재를 미리 검사하지 않는 것도
 * 같은 이유다 — AI 의 {@code INSTRUMENT_NOT_FOUND}(404) 가 내려간다 (11.2).
 * <p>
 * <b>{@code X-User-Id} 를 받지 않는다.</b> 사용자는 {@code @LoginUser}(토큰)뿐이고 서비스가 그 값으로 헤더를 새로 쓴다. 클라이언트가
 * 그 헤더를 보내도 읽히지 않는다 (aiApiSpec §4 "백엔드는 클라이언트가 보낸 X-User-Id 를 덮어쓴다").
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI 투자 비서", description = "종목 분석, 포트폴리오 진단, 브리핑과 투자 위키")
public class AiRelayController {

	private final AiRelayService relayService;
	private final WikiThesisService wikiThesisService;
	private final StockUniverse universe;

	/**
	 * 종목 범위({@link StockUniverse}) 밖은 AI 로 넘기지 않고 {@code STOCK_NOT_FOUND} 다. 위 "종목 존재를 미리 검사하지 않는다" 의
	 * 예외다 — 존재 판정이 아니라 <b>서비스 범위</b> 판정이고, 종목 분석은 호출마다 LLM 을 부르므로 범위 밖 요청이 AI 서버에 닿으면
	 * 그만큼 크레딧이 나간다. 범위 안 종목의 존재 여부는 여전히 AI 가 판정한다.
	 */
	@PostMapping("/stocks/{stockCode}/analysis")
	public ResponseEntity<JsonNode> analysis(@LoginUser long userId, @PathVariable String stockCode,
		@RequestBody(required = false) JsonNode body) {
		if (!universe.contains(stockCode)) {
			throw new CustomException(StockErrorCode.STOCK_NOT_FOUND);
		}
		return relayService.relay(AiRoute.STOCK_ANALYSIS, Map.of("ticker", stockCode), null, userId, body);
	}

	@PostMapping("/chat")
	public ResponseEntity<JsonNode> chat(@LoginUser long userId, @RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.CHAT, null, null, userId, body);
	}

	/**
	 * 대화 이력 (apiSpec 10.1, v0.8.18, 이슈 #79). 프론트가 저장해 둔 {@code conversationId} 로 채팅 화면을 복원한다. 응답은 다른 중계와 같은
	 * 봉투 재포장이다 — 대화는 {@code content.messages} 에 있다. 남의 대화·없는 대화는 AI 가 빈 {@code messages} 로 답하므로 여기서
	 * 소유권을 보지 않는다.
	 */
	@GetMapping("/chat/conversations/{conversationId}/messages")
	public ResponseEntity<JsonNode> chatMessages(@LoginUser long userId, @PathVariable String conversationId) {
		return relayService.relay(AiRoute.CHAT_CONVERSATION_MESSAGES, Map.of("conversationId", conversationId), null,
			userId, null);
	}

	@PostMapping("/portfolio/diagnosis")
	public ResponseEntity<JsonNode> diagnosis(@LoginUser long userId, @RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.PORTFOLIO_DIAGNOSIS, null, null, userId, body);
	}

	@PostMapping("/portfolio/attribution")
	public ResponseEntity<JsonNode> attribution(@LoginUser long userId, @RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.PORTFOLIO_ATTRIBUTION, null, null, userId, body);
	}

	@PostMapping("/orders/preview")
	public ResponseEntity<JsonNode> orderPreview(@LoginUser long userId, @RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.ORDER_PREVIEW, null, null, userId, body);
	}

	/** 쿼리({@code date})는 그대로 넘긴다 — 우리가 해석할 것이 없다. */
	@GetMapping("/briefing")
	public ResponseEntity<JsonNode> briefing(@LoginUser long userId, @RequestParam MultiValueMap<String, String> query) {
		return relayService.relay(AiRoute.BRIEFING, null, query, userId, null);
	}

	@PostMapping("/feedback")
	public ResponseEntity<JsonNode> feedback(@LoginUser long userId, @RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.FEEDBACK, null, null, userId, body);
	}

	@GetMapping("/wiki")
	public ResponseEntity<JsonNode> wiki(@LoginUser long userId, @RequestParam MultiValueMap<String, String> query) {
		return relayService.relay(AiRoute.WIKI, null, query, userId, null);
	}

	/**
	 * 논지 새로 기록 (apiSpec 10.1, v0.8.8). 경로 변수가 없고 종목은 본문의 {@code ticker} 다. 같은 종목의 {@code active} 논지는
	 * AI 가 {@code closed} 로 닫는다(교체) — 우리는 본문을 그대로 넘길 뿐 논지 유무를 먼저 보지 않는다.
	 */
	@PostMapping("/wiki/theses")
	public ResponseEntity<JsonNode> createThesis(@LoginUser long userId, @RequestBody(required = false) JsonNode body) {
		return wikiThesisService.create(userId, body);
	}

	/** 논지를 쓰는 두 경로는 {@link WikiThesisService} 를 지난다 — 성공하면 알림함이 쓰는 논지 캐시를 지운다 (apiSpec 6.4). */
	@PutMapping("/wiki/theses/{stockCode}")
	public ResponseEntity<JsonNode> updateThesis(@LoginUser long userId, @PathVariable String stockCode,
		@RequestBody(required = false) JsonNode body) {
		return wikiThesisService.update(userId, stockCode, body);
	}

	@DeleteMapping("/wiki/facts/{factId}")
	public ResponseEntity<JsonNode> deleteFact(@LoginUser long userId, @PathVariable String factId) {
		return relayService.relay(AiRoute.WIKI_FACT_DELETE, Map.of("factId", factId), null, userId, null);
	}

	/**
	 * 위키 추측을 사실로 확정 (apiSpec 10.1, v0.8.18, 이슈 #79). 본문이 없다. 없는 id·남의 항목·삭제된 항목은 AI 의
	 * {@code 400 INVALID_REQUEST} 한 갈래로 그대로 내려간다 — 구분하면 남의 위키 항목 존재가 드러나서 AI 가 합쳤다. 논지가 아니라 성향
	 * 항목이라 알림함 논지 캐시({@link WikiThesisService})와 무관하다.
	 */
	@PostMapping("/wiki/facts/{factId}/confirm")
	public ResponseEntity<JsonNode> confirmFact(@LoginUser long userId, @PathVariable String factId) {
		return relayService.relay(AiRoute.WIKI_FACT_CONFIRM, Map.of("factId", factId), null, userId, null);
	}
}
