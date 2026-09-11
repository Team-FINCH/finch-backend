package com.finch.domain.ai.relay;

import com.finch.global.security.LoginUser;
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
 * AI 중계 API 10종 (apiSpec 10.1). 한 컨트롤러에 모아 두는 이유 — 전부 같은 일({@link AiRelayService#relay})을 하고 다른 것은
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

	@PostMapping("/stocks/{stockCode}/analysis")
	public ResponseEntity<JsonNode> analysis(@LoginUser long userId, @PathVariable String stockCode,
		@RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.STOCK_ANALYSIS, Map.of("ticker", stockCode), null, userId, body);
	}

	@PostMapping("/chat")
	public ResponseEntity<JsonNode> chat(@LoginUser long userId, @RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.CHAT, null, null, userId, body);
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

	@PutMapping("/wiki/theses/{stockCode}")
	public ResponseEntity<JsonNode> updateThesis(@LoginUser long userId, @PathVariable String stockCode,
		@RequestBody(required = false) JsonNode body) {
		return relayService.relay(AiRoute.WIKI_THESIS_UPDATE, Map.of("ticker", stockCode), null, userId, body);
	}

	@DeleteMapping("/wiki/facts/{factId}")
	public ResponseEntity<JsonNode> deleteFact(@LoginUser long userId, @PathVariable String factId) {
		return relayService.relay(AiRoute.WIKI_FACT_DELETE, Map.of("factId", factId), null, userId, null);
	}
}
