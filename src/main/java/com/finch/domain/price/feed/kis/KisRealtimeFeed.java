package com.finch.domain.price.feed.kis;

import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.domain.price.feed.RealtimeCoverage;
import com.finch.global.lock.LeaderLock;
import com.finch.global.util.KstTime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * KIS 웹소켓 실시간 티어 ({@code finch.kis.realtime.enabled=true}). 세션 하나를 열어 설정의 종목을 체결가({@code H0STCNT0})로 등록하고,
 * 체결이 올 때마다 {@link PriceCache} 에 넣는다 — 폴링과 같은 캐시, 같은 {@link PriceEntry} 다. 소비 측은 어느 쪽이 채웠는지 모른다.
 * <p>
 * <b>리더만 붙는다.</b> 앱키 하나에 세션은 하나다 — 같은 접속키로 둘이 붙으면 먼저 붙은 쪽이 끊긴다. 감독 스레드가 매초
 * {@link LeaderLock#isLeader()} 를 보고, 리더인데 세션이 없으면 열고 리더가 아닌데 세션이 있으면 닫는다. 리더가 바뀌면 다음 초에
 * 새 리더가 이어받는다 — 넘겨줄 상태가 없다.
 * <p>
 * <b>{@link RealtimeCoverage}</b> — 세션이 살아 있고 등록이 끝난 동안만 종목을 준다. 그때 {@code KisPollingFeed} 가 그 종목을 순회에서
 * 빼고, 끊기면 빈 집합이라 폴링이 자연히 되맡는다. 웹소켓이 죽어도 시세가 멈추지 않는 이유다.
 * <p>
 * <b>{@code PriceObservedEvent} 를 내지 않는다.</b> 체결 메시지에는 종목상태코드가 없어 사유를 모른다. 사유 없이 알리면 리스너가
 * REST 현재가가 채워 둔 관리종목·투자경고 사유를 null 로 덮는다 ({@code Stock.applyQuote}). 종목 마스터 갱신은 폴링과 일봉 배치가 맡는다.
 * <p>
 * <b>접속 직후 REST 로 초기값을 한 번 채운다.</b> 등록해도 체결이 나기 전엔 메시지가 없다 — 장 마감 뒤에 뜬 서버는 30종목 캐시가 비어
 * 있다. 그래서 등록을 마치면 캐시가 빈 종목만 골라 현재가를 한 번씩 부른다. 리미터를 거치므로 초당 한도 안에서 천천히 간다.
 */
@Slf4j
public class KisRealtimeFeed implements SmartLifecycle, RealtimeCoverage {

	private final WebSocketClient client;
	private final KisApprovalKeyClient approvalKeys;
	private final KisClient kisClient;
	private final KisCredential key;
	private final PriceCache priceCache;
	private final LeaderLock leaderLock;
	private final KisProperties properties;
	private final MeterRegistry meterRegistry;
	private final Clock clock;
	private final Set<String> codes;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final AtomicBoolean connecting = new AtomicBoolean();

	private ScheduledExecutorService supervisor;
	private ExecutorService seeder;
	private volatile boolean running;
	private volatile WebSocketSession session;
	private volatile boolean subscribed;
	private volatile Instant retryNotBefore = Instant.EPOCH;

	public KisRealtimeFeed(WebSocketClient client, KisApprovalKeyClient approvalKeys, KisClient kisClient, KisKeyPool keyPool,
		PriceCache priceCache, LeaderLock leaderLock, KisProperties properties, MeterRegistry meterRegistry) {
		this(client, approvalKeys, kisClient, keyPool, priceCache, leaderLock, properties, meterRegistry, Clock.systemUTC());
	}

	public KisRealtimeFeed(WebSocketClient client, KisApprovalKeyClient approvalKeys, KisClient kisClient, KisKeyPool keyPool,
		PriceCache priceCache, LeaderLock leaderLock, KisProperties properties, MeterRegistry meterRegistry, Clock clock) {
		this.client = client;
		this.approvalKeys = approvalKeys;
		this.kisClient = kisClient;
		// 세션은 키당 하나다. 지금은 첫 키 하나로 세션 하나를 연다 — 키가 늘어 세션을 나눠야 할 때 이 자리가 바뀐다.
		this.key = keyPool.keys().getFirst();
		this.priceCache = priceCache;
		this.leaderLock = leaderLock;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
		this.codes = Set.copyOf(properties.realtime().codes());
	}

	// ---- RealtimeCoverage ----

	@Override
	public Set<String> covered() {
		WebSocketSession current = session;
		return subscribed && current != null && current.isOpen() ? codes : Set.of();
	}

	// ---- 감독 ----

	/** 매초 한 번. 리더 여부와 세션 상태를 맞춘다. 테스트가 직접 부른다. */
	void supervise() {
		WebSocketSession current = session;
		if (!leaderLock.isLeader()) {
			if (current != null && current.isOpen()) {
				log.info("KIS 실시간: 리더가 아니라 세션을 닫는다");
				close(current);
			}
			return;
		}
		if (current != null && current.isOpen()) {
			return;
		}
		if (Instant.now(clock).isBefore(retryNotBefore) || !connecting.compareAndSet(false, true)) {
			return;
		}
		connect();
	}

	private void connect() {
		String approvalKey;
		try {
			approvalKey = approvalKeys.issue(key);
		} catch (RuntimeException e) {
			connecting.set(false);
			scheduleRetry();
			log.warn("KIS 실시간: 접속키 발급 실패 — {} 뒤에 다시 시도한다", properties.realtime().reconnectDelay(), e);
			return;
		}
		String url = properties.realtime().url();
		log.info("KIS 실시간: 접속 시도 url={} key={} 종목={}개", url, key.label(), codes.size());
		client.execute(new Handler(approvalKey), url).whenComplete((opened, error) -> {
			connecting.set(false);
			if (error != null) {
				scheduleRetry();
				log.warn("KIS 실시간: 접속 실패 — {} 뒤에 다시 시도한다", properties.realtime().reconnectDelay(), error);
			}
		});
	}

	private void scheduleRetry() {
		retryNotBefore = Instant.now(clock).plus(properties.realtime().reconnectDelay());
	}

	private void close(WebSocketSession target) {
		subscribed = false;
		try {
			target.close(CloseStatus.NORMAL);
		} catch (IOException | RuntimeException e) {
			log.debug("KIS 실시간: 세션 닫기 실패 (무시)", e);
		}
		if (session == target) {
			session = null;
		}
	}

	// ---- 세션 이벤트 (테스트가 직접 부른다) ----

	/** 접속됨. 종목을 전부 등록하고 초기값 채우기를 건다. */
	void onConnected(WebSocketSession opened, String approvalKey) {
		session = opened;
		subscribed = false;
		int sent = 0;
		for (String code : properties.realtime().codes()) {
			try {
				opened.sendMessage(new TextMessage(subscribeJson(approvalKey, code, "1")));
				sent++;
			} catch (IOException | RuntimeException e) {
				log.warn("KIS 실시간: 등록 메시지 전송 실패 code={}", code, e);
			}
		}
		subscribed = sent > 0;
		log.info("KIS 실시간: 접속 완료, 등록 요청 {}건 key={}", sent, key.label());
		if (subscribed) {
			seeder().execute(this::seedMissing);
		}
	}

	/** 끊김. 폴링이 되맡도록 커버리지를 비우고, 잠시 뒤 재접속한다. */
	void onClosed(WebSocketSession closed, CloseStatus status) {
		subscribed = false;
		if (session == closed) {
			session = null;
		}
		scheduleRetry();
		log.warn("KIS 실시간: 세션 종료 status={} — {} 뒤에 다시 접속한다", status, properties.realtime().reconnectDelay());
	}

	/** 메시지 하나. JSON 이면 제어(등록 응답·PINGPONG), 아니면 체결가다. */
	void onMessage(WebSocketSession from, String payload) {
		if (KisTradeMessage.isTradeLine(payload)) {
			onTrades(payload);
			return;
		}
		onControl(from, payload);
	}

	private void onTrades(String payload) {
		List<KisTradeMessage.KisTrade> trades = KisTradeMessage.parse(payload);
		if (trades.isEmpty()) {
			count("unparsed");
			log.warn("KIS 실시간: 읽지 못한 메시지 — 필드 구성이 바뀌었는지 확인한다. head={}", head(payload));
			return;
		}
		Instant now = Instant.now(clock);
		for (KisTradeMessage.KisTrade trade : trades) {
			if (!codes.contains(trade.stockCode())) {
				count("unexpected");
				continue;
			}
			LocalDate sessionDate = trade.tradeDate() != null ? trade.tradeDate() : LocalDate.ofInstant(now, KstTime.ZONE);
			priceCache.put(trade.stockCode(), new PriceEntry(trade.currentPrice(), trade.previousClose(), now, sessionDate,
				trade.sessionOpen(), trade.sessionHigh(), trade.sessionLow(), trade.sessionVolume()));
			count("trade");
		}
	}

	private void onControl(WebSocketSession from, String payload) {
		JsonNode root;
		try {
			root = objectMapper.readTree(payload);
		} catch (RuntimeException e) {
			count("unparsed");
			log.warn("KIS 실시간: JSON 이 아닌 제어 메시지 head={}", head(payload));
			return;
		}
		String trId = root.path("header").path("tr_id").asString("");
		if ("PINGPONG".equals(trId)) {
			// 받은 그대로 되돌려 보내야 KIS 가 세션을 살려 둔다.
			try {
				from.sendMessage(new TextMessage(payload));
				count("pingpong");
			} catch (IOException | RuntimeException e) {
				log.warn("KIS 실시간: PINGPONG 회신 실패", e);
			}
			return;
		}
		String trKey = root.path("header").path("tr_key").asString("");
		String rtCd = root.path("body").path("rt_cd").asString("");
		String msg = root.path("body").path("msg1").asString("");
		if ("0".equals(rtCd)) {
			count("subscribe_ok");
			log.debug("KIS 실시간: 등록 성공 code={} msg={}", trKey, msg);
			return;
		}
		// 41건 초과·잘못된 종목코드가 여기로 온다. 세션은 유지된다 — 그 종목만 폴링이 맡는다.
		count("subscribe_rejected");
		log.warn("KIS 실시간: 등록 거부 code={} rt_cd={} msg_cd={} msg={}", trKey, rtCd,
			root.path("body").path("msg_cd").asString(""), msg);
	}

	/**
	 * 캐시가 빈 종목만 REST 현재가로 한 번 채운다. 한도 초과면 멈춘다 — 나머지는 체결이 오거나 폴링이 채운다.
	 * <p>
	 * 쓰기는 {@link PriceCache#putIfAbsent} 다. REST 왕복 사이에 체결이 먼저 들어왔으면 그쪽이 더 새 값이라 덮지 않는다 —
	 * 미리 {@code get} 으로 거른 것은 호출을 아끼려는 것이고, 덮어쓰기 방지는 원자적 쓰기가 맡는다.
	 */
	void seedMissing() {
		int filled = 0;
		for (String code : properties.realtime().codes()) {
			if (priceCache.get(code).isPresent()) {
				continue;
			}
			try {
				KisQuote quote = kisClient.currentPrice(key, code);
				Instant now = Instant.now(clock);
				if (priceCache.putIfAbsent(code, new PriceEntry(quote.currentPrice(), quote.previousClose(), now,
					LocalDate.ofInstant(now, KstTime.ZONE), quote.sessionOpen(), quote.sessionHigh(), quote.sessionLow(),
					quote.sessionVolume()))) {
					filled++;
				}
			} catch (KisException e) {
				if (e.getKind() == KisException.Kind.RATE_LIMITED) {
					log.warn("KIS 실시간: 초기값 채우기 중 한도 초과 — 남은 종목은 체결·폴링에 맡긴다 filled={}", filled);
					return;
				}
				log.warn("KIS 실시간: 초기값 조회 실패 code={} kind={}", code, e.getKind());
			} catch (RuntimeException e) {
				log.warn("KIS 실시간: 초기값 채우기 중 예외 code={}", code, e);
			}
		}
		if (filled > 0) {
			log.info("KIS 실시간: 초기값 {}종목 채움", filled);
		}
	}

	String subscribeJson(String approvalKey, String code, String trType) {
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("approval_key", approvalKey);
		header.put("custtype", "P");
		header.put("tr_type", trType);
		header.put("content-type", "utf-8");
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("tr_id", KisTradeMessage.TR_ID);
		input.put("tr_key", code);
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("header", header);
		root.put("body", Map.of("input", input));
		return objectMapper.writeValueAsString(root);
	}

	private void count(String kind) {
		Counter.builder("kis.realtime.messages")
			.description("KIS 웹소켓 수신·송신 메시지 수. 등록 거부(41건 초과)와 파싱 실패를 여기서 본다")
			.tag("key", key.label()).tag("kind", kind)
			.register(meterRegistry)
			.increment();
	}

	private static String head(String payload) {
		return payload == null ? "" : payload.substring(0, Math.min(80, payload.length()));
	}

	private ExecutorService seeder() {
		if (seeder == null) {
			seeder = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "kis-realtime-seed");
				thread.setDaemon(true);
				return thread;
			});
		}
		return seeder;
	}

	// ---- SmartLifecycle ----

	/** 테스트는 {@code auto-start: false} 다 — 폴링과 같은 스위치를 쓴다. 감독을 돌리지 않고 이벤트 메서드를 직접 부른다. */
	@Override
	public boolean isAutoStartup() {
		return properties.autoStart();
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		supervisor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "kis-realtime-feed");
			thread.setDaemon(true);
			return thread;
		});
		// 예외가 새면 ScheduledExecutorService 는 다음 실행을 조용히 멈춘다. 여기서 잡아 로그만 남긴다.
		supervisor.scheduleWithFixedDelay(this::superviseQuietly, 0, 1, TimeUnit.SECONDS);
		running = true;
		log.info("KIS 실시간 공급자 시작 url={} 종목={}개 key={}", properties.realtime().url(), codes.size(), key.label());
	}

	@Override
	public void stop() {
		WebSocketSession current = session;
		if (current != null) {
			close(current);
		}
		if (supervisor != null) {
			supervisor.shutdownNow();
			supervisor = null;
		}
		if (seeder != null) {
			seeder.shutdownNow();
			seeder = null;
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void superviseQuietly() {
		try {
			supervise();
		} catch (RuntimeException e) {
			connecting.set(false);
			log.warn("KIS 실시간: 감독 실패 — 다음 초에 다시 본다", e);
		}
	}

	/** Spring 웹소켓 콜백을 위 이벤트 메서드로 넘긴다. 접속키는 등록 메시지에 실려야 해서 핸들러가 들고 있다. */
	private final class Handler extends TextWebSocketHandler {

		private final String approvalKey;

		private Handler(String approvalKey) {
			this.approvalKey = approvalKey;
		}

		@Override
		public void afterConnectionEstablished(WebSocketSession opened) {
			onConnected(opened, approvalKey);
		}

		@Override
		protected void handleTextMessage(WebSocketSession from, TextMessage message) {
			onMessage(from, message.getPayload());
		}

		@Override
		public void handleTransportError(WebSocketSession from, Throwable exception) {
			log.warn("KIS 실시간: 전송 오류", exception);
		}

		@Override
		public void afterConnectionClosed(WebSocketSession closed, CloseStatus status) {
			onClosed(closed, status);
		}
	}
}
