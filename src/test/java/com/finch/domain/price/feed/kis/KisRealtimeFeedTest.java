package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.global.lock.LeaderLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

/**
 * 웹소켓 세션을 열지 않고 세션·클라이언트를 목으로 둔다. 캐시는 컨테이너의 진짜 Redis 다 — "체결이 캐시에 그대로 들어간다" 는 그것으로 본다.
 * <p>
 * 고정하는 것: 리더만 접속하는 것 · 접속 직후 종목마다 등록 메시지 1건 · 체결 줄이 캐시로 · PINGPONG 회신 · 등록 거부는 세션을 유지 ·
 * 끊기면 커버리지가 비는 것 · 접속 직후 캐시가 빈 종목만 REST 로 채우는 것.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KisRealtimeFeedTest {

	private static final AtomicLong CODE_SEQ = new AtomicLong(900_000L);
	private static final KisCredential KEY = new KisCredential("ka", "sa", "rt");
	private static final Instant NOW = Instant.parse("2026-09-14T06:12:30Z");
	private static final String BODY = "^151230^255500^2^3500^1.39^254812^252000^256500^251500^255600^255500^150"
		+ "^12345678^3140000000000^8120^9540^1420^117.5^5800000^6545678^1^54.2^93.1^090012^2^3500^104455^5^-1000^092310^2^4000"
		+ "^20260914^20^N^15230^28400^812000^1035000^0.21^11800000^104.6^0^^^2";

	@Autowired
	private PriceCache priceCache;

	private WebSocketHandler handler;

	@Test
	@DisplayName("리더가 아니면 접속키도 세션도 만들지 않는다")
	void nonLeaderDoesNotConnect() {
		WebSocketClient client = mock(WebSocketClient.class);
		KisApprovalKeyClient approval = mock(KisApprovalKeyClient.class);
		KisRealtimeFeed feed = feed(client, approval, mock(KisClient.class), List.of(newCode()), false);

		feed.supervise();

		verifyNoInteractions(client, approval);
		assertThat(feed.covered()).isEmpty();
	}

	@Test
	@DisplayName("리더면 접속키를 받아 접속하고, 열리면 종목마다 H0STCNT0 등록 메시지를 보낸다. 그때부터 커버리지에 종목이 있다")
	void connectsAndSubscribesAll() throws Exception {
		String c1 = newCode();
		String c2 = newCode();
		WebSocketSession session = openSession();
		WebSocketClient client = connectingTo(session);
		KisApprovalKeyClient approval = mock(KisApprovalKeyClient.class);
		given(approval.issue(KEY)).willReturn("ak-1");
		KisClient kis = mock(KisClient.class);
		given(kis.currentPrice(any(), any())).willReturn(new KisQuote(1_000, 1_000L, false, null));
		KisRealtimeFeed feed = feed(client, approval, kis, List.of(c1, c2), true);

		feed.supervise();
		assertThat(handler).isNotNull();
		handler.afterConnectionEstablished(session);

		ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, timeout(1_000).times(2)).sendMessage(messages.capture());
		assertThat(messages.getAllValues().get(0).getPayload())
			.contains("\"approval_key\":\"ak-1\"").contains("\"tr_type\":\"1\"")
			.contains("\"tr_id\":\"H0STCNT0\"").contains("\"tr_key\":\"" + c1 + "\"");
		assertThat(messages.getAllValues().get(1).getPayload()).contains("\"tr_key\":\"" + c2 + "\"");
		assertThat(feed.covered()).containsExactlyInAnyOrder(c1, c2);

		// 두 번째 감독은 이미 열려 있으니 아무것도 하지 않는다.
		feed.supervise();
		verify(approval).issue(KEY);
	}

	@Test
	@DisplayName("체결 줄이 오면 캐시에 현재가·전일종가·당일 봉이 들어간다. 등록하지 않은 종목의 줄은 버린다")
	void tradeLineFillsCache() throws Exception {
		String code = newCode();
		String stranger = newCode();
		WebSocketSession session = openSession();
		KisRealtimeFeed feed = feed(connectingTo(session), approvalOf("ak"), quietKis(), List.of(code), true);
		feed.supervise();
		handler.afterConnectionEstablished(session);

		handler.handleMessage(session, new TextMessage("0|H0STCNT0|001|" + code + BODY));
		handler.handleMessage(session, new TextMessage("0|H0STCNT0|001|" + stranger + BODY));

		PriceEntry entry = priceCache.get(code).orElseThrow();
		assertThat(entry.currentPrice()).isEqualTo(255_500);
		assertThat(entry.previousClose()).isEqualTo(252_000L);
		assertThat(entry.asOf()).isEqualTo(NOW);
		assertThat(entry.sessionDate()).isEqualTo(LocalDate.of(2026, 9, 14));
		assertThat(entry.sessionOpen()).isEqualTo(252_000L);
		assertThat(entry.sessionHigh()).isEqualTo(256_500L);
		assertThat(entry.sessionLow()).isEqualTo(251_500L);
		assertThat(entry.sessionVolume()).isEqualTo(12_345_678L);
		assertThat(priceCache.get(stranger)).isEmpty();
	}

	@Test
	@DisplayName("PINGPONG 은 받은 그대로 되돌려 보내고, 등록 거부 응답은 세션을 유지한 채 로그·메트릭만 남긴다")
	void controlMessages() throws Exception {
		String code = newCode();
		WebSocketSession session = openSession();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		KisRealtimeFeed feed = feed(connectingTo(session), approvalOf("ak"), quietKis(), List.of(code), true, registry);
		feed.supervise();
		handler.afterConnectionEstablished(session);

		String ping = "{\"header\":{\"tr_id\":\"PINGPONG\",\"datetime\":\"20260914151300\"}}";
		handler.handleMessage(session, new TextMessage(ping));
		verify(session, timeout(1_000)).sendMessage(new TextMessage(ping));

		handler.handleMessage(session, new TextMessage(
			"{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"" + code + "\"},\"body\":{\"rt_cd\":\"9\",\"msg_cd\":\"OPSP0002\",\"msg1\":\"MAX SUBSCRIBE OVER\"}}"));
		handler.handleMessage(session, new TextMessage(
			"{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"" + code + "\"},\"body\":{\"rt_cd\":\"0\",\"msg_cd\":\"OPSP0000\",\"msg1\":\"SUBSCRIBE SUCCESS\"}}"));

		assertThat(registry.counter("kis.realtime.messages", "key", "rt", "kind", "subscribe_rejected").count()).isEqualTo(1);
		assertThat(registry.counter("kis.realtime.messages", "key", "rt", "kind", "subscribe_ok").count()).isEqualTo(1);
		assertThat(registry.counter("kis.realtime.messages", "key", "rt", "kind", "pingpong").count()).isEqualTo(1);
		verify(session, never()).close(any());
		assertThat(feed.covered()).contains(code);
	}

	@Test
	@DisplayName("세션이 닫히면 커버리지가 비어 폴링이 되맡고, 재접속 지연이 지나기 전에는 다시 붙지 않는다")
	void closedSessionEmptiesCoverage() throws Exception {
		String code = newCode();
		WebSocketSession session = openSession();
		WebSocketClient client = connectingTo(session);
		KisRealtimeFeed feed = feed(client, approvalOf("ak"), quietKis(), List.of(code), true);
		feed.supervise();
		handler.afterConnectionEstablished(session);
		assertThat(feed.covered()).contains(code);

		given(session.isOpen()).willReturn(false);
		handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);

		assertThat(feed.covered()).isEmpty();
		feed.supervise();
		verify(client).execute(any(WebSocketHandler.class), anyString());
	}

	@Test
	@DisplayName("접속 직후 캐시가 빈 종목만 REST 현재가로 한 번 채운다")
	void seedsMissingOnConnect() throws Exception {
		String cached = newCode();
		String missing = newCode();
		priceCache.put(cached, new PriceEntry(10, 10L, NOW));
		WebSocketSession session = openSession();
		KisClient kis = mock(KisClient.class);
		given(kis.currentPrice(eq(KEY), eq(missing))).willReturn(new KisQuote(70_000, 69_000L, false, null,
			69_500L, 70_500L, 68_900L, 12L));
		KisRealtimeFeed feed = feed(connectingTo(session), approvalOf("ak"), kis, List.of(cached, missing), true);

		feed.supervise();
		handler.afterConnectionEstablished(session);

		verify(kis, timeout(2_000)).currentPrice(KEY, missing);
		verify(kis, never()).currentPrice(KEY, cached);
		PriceEntry seeded = await(missing);
		assertThat(seeded.currentPrice()).isEqualTo(70_000);
		assertThat(seeded.previousClose()).isEqualTo(69_000L);
		assertThat(seeded.sessionVolume()).isEqualTo(12L);
	}

	@Test
	@DisplayName("REST 왕복 사이에 체결이 먼저 들어오면 초기값이 그 값을 덮지 않는다")
	void seedDoesNotOverwriteTradeThatArrivedMeanwhile() {
		String code = newCode();
		WebSocketSession session = openSession();
		KisClient kis = mock(KisClient.class);
		KisRealtimeFeed feed = feed(connectingTo(session), approvalOf("ak"), kis, List.of(code), true);
		// REST 가 답하는 동안 체결이 도착한 상황 — 목이 응답하기 전에 캐시에 체결 값을 넣는다.
		given(kis.currentPrice(KEY, code)).willAnswer(invocation -> {
			feed.onMessage(session, "0|H0STCNT0|001|" + code + BODY);
			return new KisQuote(1, 1L, false, null);
		});

		feed.seedMissing();

		assertThat(priceCache.get(code).orElseThrow().currentPrice()).isEqualTo(255_500);
	}

	// ---- helpers ----

	private PriceEntry await(String code) throws InterruptedException {
		for (int i = 0; i < 40; i++) {
			var entry = priceCache.get(code);
			if (entry.isPresent()) {
				return entry.get();
			}
			Thread.sleep(50);
		}
		throw new AssertionError("캐시가 채워지지 않았다 code=" + code);
	}

	private KisRealtimeFeed feed(WebSocketClient client, KisApprovalKeyClient approval, KisClient kis, List<String> codes,
		boolean leader) {
		return feed(client, approval, kis, codes, leader, new SimpleMeterRegistry());
	}

	private KisRealtimeFeed feed(WebSocketClient client, KisApprovalKeyClient approval, KisClient kis, List<String> codes,
		boolean leader, SimpleMeterRegistry registry) {
		LeaderLock lock = mock(LeaderLock.class);
		given(lock.isLeader()).willReturn(leader);
		KisProperties props = new KisProperties("https://kis.test", List.of(KEY), Duration.ofSeconds(3), 20,
			Duration.ofSeconds(5), Duration.ofMinutes(5), false,
			new KisProperties.Realtime(true, "ws://kis.test:31000", codes, Duration.ofSeconds(5)));
		return new KisRealtimeFeed(client, approval, kis, new KisKeyPool(List.of(KEY)), priceCache, lock, props, registry,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	/** 접속하면 핸들러를 잡아 두고 곧바로 열린 세션을 준다. 실제 핸드셰이크는 없다. */
	private WebSocketClient connectingTo(WebSocketSession session) {
		WebSocketClient client = mock(WebSocketClient.class);
		given(client.execute(any(WebSocketHandler.class), anyString())).willAnswer(invocation -> {
			handler = invocation.getArgument(0);
			return CompletableFuture.completedFuture(session);
		});
		return client;
	}

	private static WebSocketSession openSession() {
		WebSocketSession session = mock(WebSocketSession.class);
		given(session.isOpen()).willReturn(true);
		return session;
	}

	private static KisApprovalKeyClient approvalOf(String key) {
		KisApprovalKeyClient approval = mock(KisApprovalKeyClient.class);
		given(approval.issue(any())).willReturn(key);
		return approval;
	}

	/** 초기값 채우기가 조용히 지나가게 — 모든 종목이 캐시에 없다고 해도 REST 가 값을 준다. */
	private static KisClient quietKis() {
		KisClient kis = mock(KisClient.class);
		given(kis.currentPrice(any(), any())).willReturn(new KisQuote(1, 1L, false, null));
		return kis;
	}

	private static String newCode() {
		return String.valueOf(CODE_SEQ.incrementAndGet());
	}
}
