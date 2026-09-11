package com.finch.domain.ai.service;

import com.finch.domain.ai.relay.AiRelayService;
import com.finch.domain.ai.relay.AiRoute;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 위키 투자 논지 — 알림함이 "논지가 있는 종목" 을 묻는 창구이고, 논지를 쓰는 중계 두 개(POST·PUT)가 지나는 자리다 (apiSpec 6.4 · 10.1).
 * <p>
 * <b>논지 목록을 사용자별로 캐시한다.</b> 알림함 뱃지는 홈·포트폴리오·내 정보 헤더에서 불리는데, 매번 AI 위키를 부르면 AI 가 느리거나
 * 죽었을 때 뱃지까지 같이 멈춘다. {@value #FRESH_MINUTES}분 안의 값은 AI 를 부르지 않고 쓴다.
 * <p>
 * <b>논지를 쓰는 중계가 성공하면 캐시를 지운다.</b> 알림함 시트에서 이유를 저장한 직후 목록을 다시 불러도 그 항목이 빠져야 한다.
 * 중계를 이 서비스가 감싸는 이유가 그것이다 — {@code AiRelayService} 는 제네릭 프록시라 논지를 모른다. AI 채팅 안에서 AI 가 스스로
 * 기록한 논지는 백엔드를 지나지 않아 알 수 없고, 그때는 캐시가 낡는 {@value #FRESH_MINUTES}분 뒤에 반영된다 (apiSpec 6.4 에 적었다).
 * <p>
 * <b>AI 를 못 읽으면 마지막 값을 쓴다.</b> 신선 기간이 지나도 값은 {@value #KEEP_HOURS}시간 남겨 두고, 새로 읽기에 실패했을 때만 꺼낸다.
 * 그것마저 없으면 {@link Optional#empty()} 다 — 호출자가 "모른다" 를 다룬다.
 */
@Slf4j
@Service
public class WikiThesisService {

	static final String KEY_PREFIX = "ai:wiki:active-theses:";
	static final int FRESH_MINUTES = 5;
	static final int KEEP_HOURS = 24;
	private static final Duration FRESH = Duration.ofMinutes(FRESH_MINUTES);
	private static final Duration KEEP = Duration.ofHours(KEEP_HOURS);

	private final AiRelayService relayService;
	private final StringRedisTemplate redisTemplate;
	private final Clock clock;
	/** 캐시는 넣은 그대로 되받는 것이 목적이라 앱의 매퍼를 쓰지 않는다 ({@code PriceCache} 와 같은 이유). */
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	@Autowired
	public WikiThesisService(AiRelayService relayService, StringRedisTemplate redisTemplate) {
		this(relayService, redisTemplate, Clock.systemUTC());
	}

	/** 신선 기간 경계를 시각을 고정해 보려는 테스트가 쓴다. */
	public WikiThesisService(AiRelayService relayService, StringRedisTemplate redisTemplate, Clock clock) {
		this.relayService = relayService;
		this.redisTemplate = redisTemplate;
		this.clock = clock;
	}

	/** 논지 새로 기록 (apiSpec 10.1 POST). 중계가 성공하면 캐시를 지운다 — 실패하면 예외가 먼저 나가 캐시는 그대로다. */
	public ResponseEntity<JsonNode> create(long userId, JsonNode body) {
		ResponseEntity<JsonNode> response = relayService.relay(AiRoute.WIKI_THESIS_CREATE, null, null, userId, body);
		evict(userId);
		return response;
	}

	/** 논지 수정 (apiSpec 10.1 PUT). 캐시 처리는 {@link #create} 와 같다. */
	public ResponseEntity<JsonNode> update(long userId, String stockCode, JsonNode body) {
		ResponseEntity<JsonNode> response =
			relayService.relay(AiRoute.WIKI_THESIS_UPDATE, Map.of("ticker", stockCode), null, userId, body);
		evict(userId);
		return response;
	}

	/**
	 * {@code active} 논지가 있는 종목코드. 알림함 "적어야 할 것" 의 판정 기준이다 (apiSpec 6.4).
	 *
	 * @return 신선한 캐시 → 그 값. 아니면 AI 위키를 읽어 캐시하고 그 값. 읽기에 실패하면 남아 있는 옛 값, 그것도 없으면 empty.
	 */
	public Optional<Set<String>> activeThesisTickers(long userId) {
		Instant now = Instant.now(clock);
		Cached cached = readCache(userId);
		if (cached != null && now.isBefore(cached.fetchedAt().plus(FRESH))) {
			return Optional.of(Set.copyOf(cached.tickers()));
		}
		try {
			Set<String> tickers = fetch(userId);
			writeCache(userId, new Cached(List.copyOf(tickers), now));
			return Optional.of(tickers);
		} catch (RuntimeException e) {
			log.warn("AI 위키를 읽지 못했다 — 남은 값을 쓴다. userId={} cached={}", userId, cached != null, e);
			return cached == null ? Optional.empty() : Optional.of(Set.copyOf(cached.tickers()));
		}
	}

	void evict(long userId) {
		redisTemplate.delete(KEY_PREFIX + userId);
	}

	/**
	 * AI {@code GET /wiki} 의 {@code content.theses[]} 중 {@code status = active} 인 {@code ticker}. 중계 경로를 그대로 타므로 봉투는
	 * 이미 벗겨지고 키는 camel 이다 (apiSpec 10.3). {@code theses} 가 없으면 AI 응답 모양이 바뀐 것이라 실패로 본다 — 빈 목록으로 두면
	 * 모든 보유 종목에 "왜 담으셨나요?" 가 뜬다.
	 */
	private Set<String> fetch(long userId) {
		JsonNode body = relayService.relay(AiRoute.WIKI, null, null, userId, null).getBody();
		JsonNode theses = body == null ? null : body.path("content").get("theses");
		if (theses == null || !theses.isArray()) {
			throw new IllegalStateException("AI 위키 응답에 content.theses 가 없다");
		}
		Set<String> tickers = new LinkedHashSet<>();
		for (JsonNode thesis : theses) {
			String ticker = thesis.path("ticker").stringValue(null);
			if ("active".equals(thesis.path("status").stringValue(null)) && ticker != null) {
				tickers.add(ticker);
			}
		}
		return tickers;
	}

	/** 읽지 못한 값은 없는 것으로 본다 — 캐시가 깨졌다고 알림함이 500 을 내면 뱃지가 사라진다. */
	private Cached readCache(long userId) {
		String json = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, Cached.class);
		} catch (RuntimeException e) {
			log.warn("논지 캐시를 읽지 못했다 — 없는 것으로 본다. userId={}", userId, e);
			return null;
		}
	}

	private void writeCache(long userId, Cached value) {
		redisTemplate.opsForValue().set(KEY_PREFIX + userId, objectMapper.writeValueAsString(value), KEEP);
	}

	/** 캐시 값. {@code fetchedAt} 으로 신선 기간을 판정한다 — Redis TTL 은 "남겨 둘 기간" 이지 신선 기간이 아니다. */
	record Cached(List<String> tickers, Instant fetchedAt) {
	}
}
