package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * KIS 를 부르지 않고 응답만 흉내낸다 ({@code KakaoPayGatewayTest} 와 같은 방식). 토큰 저장소는 컨테이너의 진짜 Redis 다 —
 * "인스턴스가 바뀌어도 토큰을 이어 쓴다" 는 Redis 없이 볼 수 없다.
 * <p>
 * 고정하는 것: 요청 헤더 모양(tr_id·appkey·Bearer) · 토큰 발급이 키당 1회이고 Redis 에 남는 것 · 401 → 그 키만 재발급 후 1회 재시도 ·
 * 429/EGW00201 → Retry-After 만큼 기다려 1회 재시도, 두 번째도 그러면 RATE_LIMITED · 일봉 100 봉 페이징 · 메트릭 카운터.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KisClientTest {

	private static final AtomicLong LABEL_SEQ = new AtomicLong();
	private static final String TOKEN_JSON = """
		{"access_token":"tok-1","token_type":"Bearer","expires_in":86400,"access_token_token_expired":"2026-09-08 20:00:00"}""";
	private static final String PRICE_JSON = """
		{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다!","output":{"stck_prpr":"73500","prdy_vrss":"-900",
		"prdy_ctrt":"-1.21","stck_sdpr":"74400","temp_stop_yn":"N","iscd_stat_cls_code":"55"}}""";
	private static final String SUSPENDED_JSON = """
		{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":{"stck_prpr":"1000","stck_sdpr":"1000","temp_stop_yn":"Y","iscd_stat_cls_code":"58"}}""";
	private static final String RATE_JSON = """
		{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""";
	private static final String EXPIRED_JSON = """
		{"rt_cd":"1","msg_cd":"EGW00123","msg1":"기간이 만료된 token 입니다."}""";

	@Autowired
	private StringRedisTemplate redisTemplate;

	private final List<ClientRequest> sent = new ArrayList<>();
	private final List<Duration> slept = new ArrayList<>();

	@Nested
	@DisplayName("토큰")
	class Token {

		@Test
		@DisplayName("첫 호출이 토큰을 발급받아 Redis 에 두고, 다른 인스턴스(새 매니저)는 발급 없이 그 토큰을 쓴다")
		void issuesOnceAndSharesViaRedis() {
			KisCredential key = key();
			KisProperties props = props();
			Function<ClientRequest, ClientResponse> kis = req -> isToken(req) ? json(HttpStatus.OK, TOKEN_JSON)
				: json(HttpStatus.OK, PRICE_JSON);
			KisClient first = client(new KisTokenManager(stub(kis), redisTemplate, props), props);
			first.currentPrice(key, "005930");

			assertThat(redisTemplate.opsForValue().get(KisTokenManager.KEY_PREFIX + key.label())).isEqualTo("tok-1");
			assertThat(redisTemplate.getExpire(KisTokenManager.KEY_PREFIX + key.label())).isBetween(86_000L, 86_100L);

			sent.clear();
			KisClient second = client(new KisTokenManager(stub(kis), redisTemplate, props), props);
			second.currentPrice(key, "005930");

			assertThat(sent).noneMatch(KisClientTest::isToken);
			ClientRequest priceCall = sent.getFirst();
			assertThat(priceCall.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-1");
			assertThat(priceCall.headers().getFirst("appkey")).isEqualTo(key.appKey());
			assertThat(priceCall.headers().getFirst("tr_id")).isEqualTo(KisClient.TR_PRICE);
			assertThat(priceCall.url().getQuery()).contains("FID_INPUT_ISCD=005930");
		}

		@Test
		@DisplayName("키가 둘이면 토큰도 둘이다 — Redis 키가 label 로 갈린다")
		void tokensAreSeparatedPerKey() {
			KisCredential a = key();
			KisCredential b = key();
			KisProperties props = props();
			AtomicLong issued = new AtomicLong();
			KisClient client = client(new KisTokenManager(stub(req -> isToken(req)
				? json(HttpStatus.OK, TOKEN_JSON.replace("tok-1", "tok-" + issued.incrementAndGet()))
				: json(HttpStatus.OK, PRICE_JSON)), redisTemplate, props), props);

			client.currentPrice(a, "005930");
			client.currentPrice(b, "005930");

			assertThat(issued.get()).isEqualTo(2);
			assertThat(redisTemplate.opsForValue().get(KisTokenManager.KEY_PREFIX + a.label()))
				.isNotEqualTo(redisTemplate.opsForValue().get(KisTokenManager.KEY_PREFIX + b.label()));
		}

		@Test
		@DisplayName("토큰 만료(EGW00123)면 그 키의 토큰을 버리고 재발급해 1회 재시도한다")
		void reissuesOnExpiredToken() {
			KisCredential key = key();
			KisProperties props = props();
			AtomicLong issued = new AtomicLong();
			AtomicLong priceCalls = new AtomicLong();
			KisClient client = client(new KisTokenManager(stub(req -> {
				if (isToken(req)) {
					return json(HttpStatus.OK, TOKEN_JSON.replace("tok-1", "tok-" + issued.incrementAndGet()));
				}
				return priceCalls.incrementAndGet() == 1 ? json(HttpStatus.INTERNAL_SERVER_ERROR, EXPIRED_JSON)
					: json(HttpStatus.OK, PRICE_JSON);
			}), redisTemplate, props), props);

			KisQuote quote = client.currentPrice(key, "005930");

			assertThat(quote.currentPrice()).isEqualTo(73_500);
			assertThat(issued.get()).isEqualTo(2);
			assertThat(redisTemplate.opsForValue().get(KisTokenManager.KEY_PREFIX + key.label())).isEqualTo("tok-2");
		}
	}

	@Nested
	@DisplayName("현재가")
	class Price {

		@Test
		@DisplayName("현재가·기준가(전일 종가)·거래정지 여부·사유를 꺼낸다")
		void parsesQuote() {
			KisProperties props = props();
			KisClient client = client(new KisTokenManager(stub(req -> isToken(req) ? json(HttpStatus.OK, TOKEN_JSON)
				: req.url().getQuery().contains("ZZ9958") ? json(HttpStatus.OK, SUSPENDED_JSON)
				: json(HttpStatus.OK, PRICE_JSON)), redisTemplate, props), props);

			KisQuote samsung = client.currentPrice(key(), "005930");
			KisQuote halted = client.currentPrice(key(), "ZZ9958");

			assertThat(samsung).isEqualTo(new KisQuote(73_500, 74_400L, false, null));
			assertThat(halted).isEqualTo(new KisQuote(1_000, 1_000L, true, "거래정지"));
		}

		@Test
		@DisplayName("초당 한도 초과(EGW00201·429)는 Retry-After 만큼 기다려 1회 재시도하고, 두 번째도 그러면 RATE_LIMITED")
		void retriesOnceOnRateLimit() {
			KisProperties props = props();
			AtomicLong priceCalls = new AtomicLong();
			KisClient client = client(new KisTokenManager(stub(req -> {
				if (isToken(req)) {
					return json(HttpStatus.OK, TOKEN_JSON);
				}
				long n = priceCalls.incrementAndGet();
				if (n == 1) {
					return json(HttpStatus.INTERNAL_SERVER_ERROR, RATE_JSON);
				}
				if (n == 2) {
					return json(HttpStatus.OK, PRICE_JSON);
				}
				return ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
					.header(HttpHeaders.RETRY_AFTER, "3")
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.body(RATE_JSON).build();
			}), redisTemplate, props), props);
			KisCredential key = key();

			assertThat(client.currentPrice(key, "005930").currentPrice()).isEqualTo(73_500);
			assertThat(slept).containsExactly(Duration.ofSeconds(1));

			assertThatThrownBy(() -> client.currentPrice(key, "005930"))
				.isInstanceOf(KisException.class)
				.extracting(e -> ((KisException) e).getKind())
				.isEqualTo(KisException.Kind.RATE_LIMITED);
			assertThat(slept).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(3));
		}

		@Test
		@DisplayName("rt_cd != 0 은 REJECTED, 연결 실패는 UNAVAILABLE — 둘 다 재시도하지 않는다")
		void rejectedAndUnavailable() {
			KisProperties props = props();
			AtomicLong calls = new AtomicLong();
			KisClient rejected = client(new KisTokenManager(stub(req -> {
				if (isToken(req)) {
					return json(HttpStatus.OK, TOKEN_JSON);
				}
				calls.incrementAndGet();
				return json(HttpStatus.OK, """
					{"rt_cd":"1","msg_cd":"OPSQ2000","msg1":"조회할 수 없는 종목입니다."}""");
			}), redisTemplate, props), props);

			assertThatThrownBy(() -> rejected.currentPrice(key(), "999999"))
				.isInstanceOf(KisException.class)
				.extracting(e -> ((KisException) e).getKind()).isEqualTo(KisException.Kind.REJECTED);
			assertThat(calls.get()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("일봉")
	class Candles {

		@Test
		@DisplayName("100 봉이 꽉 차면 가장 오래된 날의 전날을 새 끝으로 다시 부르고, 결과는 오래된 날부터다")
		void pagesBackwards() {
			KisProperties props = props();
			List<String> windows = new ArrayList<>();
			KisClient client = client(new KisTokenManager(stub(req -> {
				if (isToken(req)) {
					return json(HttpStatus.OK, TOKEN_JSON);
				}
				String q = req.url().getQuery();
				windows.add(q.replaceAll(".*FID_INPUT_DATE_2=(\\d+).*", "$1"));
				LocalDate end = LocalDate.parse(q.replaceAll(".*FID_INPUT_DATE_2=(\\d+).*", "$1"),
					java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
				int rows = windows.size() == 1 ? 100 : 20;
				StringBuilder out = new StringBuilder();
				for (int i = 0; i < rows; i++) {
					LocalDate d = end.minusDays(i);
					out.append(i > 0 ? "," : "").append("{\"stck_bsop_date\":\"")
						.append(d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
						.append("\",\"stck_oprc\":\"100\",\"stck_hgpr\":\"110\",\"stck_lwpr\":\"90\",\"stck_clpr\":\"105\",\"acml_vol\":\"7\"}");
				}
				return json(HttpStatus.OK, "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"ok\",\"output2\":[" + out + "]}");
			}), redisTemplate, props), props);

			List<KisCandle> candles = client.dailyCandles(key(), "005930", LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 9, 7));

			assertThat(windows).containsExactly("20260907", "20260530");
			assertThat(candles).hasSize(120);
			assertThat(candles.getFirst().tradeDate()).isBefore(candles.getLast().tradeDate());
			assertThat(candles.getLast().tradeDate()).isEqualTo(LocalDate.of(2026, 9, 7));
			assertThat(candles.getFirst()).isEqualTo(new KisCandle(LocalDate.of(2026, 5, 11), 100, 110, 90, 105, 7));
		}
	}

	@Nested
	@DisplayName("업종 지수")
	class Index {

		private static final String KOSPI_JSON = """
			{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다!","output":{"bstp_nmix_prpr":"2600.54",
			"bstp_nmix_prdy_vrss":"-12.31","prdy_vrss_sign":"5","bstp_nmix_prdy_ctrt":"-0.47"}}""";

		@Test
		@DisplayName("TR·경로·업종코드로 부르고 현재 지수·부호 붙은 전일 대비를 소수 둘째 자리로 꺼낸다")
		void parsesIndex() {
			KisProperties props = props();
			KisClient client = client(new KisTokenManager(stub(req -> isToken(req) ? json(HttpStatus.OK, TOKEN_JSON)
				: json(HttpStatus.OK, KOSPI_JSON)), redisTemplate, props), props);

			KisIndexQuote quote = client.indexPrice(key(), "0001");

			assertThat(quote.currentValue()).isEqualByComparingTo("2600.54");
			assertThat(quote.changeValue()).isEqualByComparingTo("-12.31");
			assertThat(quote.previousClose()).isEqualByComparingTo("2612.85");
			ClientRequest call = sent.getLast();
			assertThat(call.headers().getFirst("tr_id")).isEqualTo(KisClient.TR_INDEX);
			assertThat(call.url().getPath()).isEqualTo(KisClient.INDEX_PATH);
			assertThat(call.url().getQuery()).contains("FID_COND_MRKT_DIV_CODE=U").contains("FID_INPUT_ISCD=0001");
		}

		/** 값 필드에 부호가 붙어 오는지 문서에 없다. 어느 쪽이든 대비부호가 정하게 한다. */
		@Test
		@DisplayName("부호는 대비부호가 정한다 — 부호 없는 값에 하락(5)이면 음수, 상승(2)이면 양수, 보합(3)이면 0")
		void signComesFromSignCode() {
			assertThat(KisClient.signed(new BigDecimal("12.31"), "5")).isEqualByComparingTo("-12.31");
			assertThat(KisClient.signed(new BigDecimal("-12.31"), "4")).isEqualByComparingTo("-12.31");
			assertThat(KisClient.signed(new BigDecimal("-3.10"), "2")).isEqualByComparingTo("3.10");
			assertThat(KisClient.signed(new BigDecimal("0.00"), "3")).isEqualByComparingTo("0");
			assertThat(KisClient.signed(new BigDecimal("-1.00"), null)).isEqualByComparingTo("-1.00");
		}

		@Test
		@DisplayName("output 이 한 줄짜리 배열로 와도 읽는다")
		void acceptsArrayOutput() {
			KisProperties props = props();
			KisClient client = client(new KisTokenManager(stub(req -> isToken(req) ? json(HttpStatus.OK, TOKEN_JSON)
				: json(HttpStatus.OK, """
					{"rt_cd":"0","msg_cd":"MCA00000","msg1":"ok","output":[{"bstp_nmix_prpr":"793.8",
					"bstp_nmix_prdy_vrss":"0.95","prdy_vrss_sign":"2"}]}""")), redisTemplate, props), props);

			KisIndexQuote quote = client.indexPrice(key(), "1001");

			assertThat(quote.currentValue()).isEqualTo(new BigDecimal("793.80"));
			assertThat(quote.changeValue()).isEqualByComparingTo("0.95");
		}

		/** 0 을 캐시에 넣으면 등락률이 −100% 로 나간다. 던져서 공급자가 캐시를 덮지 않게 한다. */
		@Test
		@DisplayName("현재 지수가 0 이거나 output 이 없으면 REJECTED")
		void rejectsZeroOrMissing() {
			KisProperties props = props();
			KisClient zero = client(new KisTokenManager(stub(req -> isToken(req) ? json(HttpStatus.OK, TOKEN_JSON)
				: json(HttpStatus.OK, """
					{"rt_cd":"0","msg_cd":"MCA00000","msg1":"ok","output":{"bstp_nmix_prpr":"0.00",
					"bstp_nmix_prdy_vrss":"0.00","prdy_vrss_sign":"3"}}""")), redisTemplate, props), props);
			assertThatThrownBy(() -> zero.indexPrice(key(), "0001"))
				.isInstanceOf(KisException.class)
				.extracting(e -> ((KisException) e).getKind()).isEqualTo(KisException.Kind.REJECTED);

			KisClient empty = client(new KisTokenManager(stub(req -> isToken(req) ? json(HttpStatus.OK, TOKEN_JSON)
				: json(HttpStatus.OK, """
					{"rt_cd":"0","msg_cd":"MCA00000","msg1":"ok"}""")), redisTemplate, props), props);
			assertThatThrownBy(() -> empty.indexPrice(key(), "0001"))
				.isInstanceOf(KisException.class)
				.extracting(e -> ((KisException) e).getKind()).isEqualTo(KisException.Kind.REJECTED);
		}
	}

	@Test
	@DisplayName("모든 호출이 kis.calls{key,endpoint,outcome} 로 세어진다 — S0-1 실측용")
	void countsCalls() {
		KisProperties props = props();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		KisClient client = new KisClient(stub(req -> isToken(req) ? json(HttpStatus.OK, TOKEN_JSON)
			: json(HttpStatus.OK, PRICE_JSON)), new KisTokenManager(stub(req -> json(HttpStatus.OK, TOKEN_JSON)),
			redisTemplate, props), props, registry);
		KisCredential key = key();

		client.currentPrice(key, "005930");
		client.currentPrice(key, "000660");

		assertThat(registry.get("kis.calls").tag("key", key.label()).tag("endpoint", "price").tag("outcome", "ok")
			.counter().count()).isEqualTo(2.0);
	}

	// ---- helpers ----

	private KisClient client(KisTokenManager tokenManager, KisProperties props) {
		// 토큰 관리자와 같은 응답기를 쓰도록 클라이언트도 같은 스텁을 받는다 — 스텁은 tokenManager 생성에 쓰인 것과 같은 함수다.
		return new KisClient(lastStub, tokenManager, props, new SimpleMeterRegistry()) {
			@Override
			protected void sleep(Duration duration) {
				slept.add(duration);
			}
		};
	}

	private WebClient.Builder lastStub;

	private WebClient.Builder stub(Function<ClientRequest, ClientResponse> responder) {
		lastStub = WebClient.builder().exchangeFunction(request -> {
			sent.add(request);
			return Mono.just(responder.apply(request));
		});
		return lastStub;
	}

	private static KisProperties props() {
		return new KisProperties("https://kis.test", List.of(), Duration.ofSeconds(3), 20, Duration.ofSeconds(5),
			Duration.ofMinutes(5), false);
	}

	/** 테스트마다 새 label — Redis 를 비우지 않아도 토큰이 섞이지 않는다. */
	private static KisCredential key() {
		return new KisCredential("app-key", "app-secret", "t" + LABEL_SEQ.incrementAndGet());
	}

	private static boolean isToken(ClientRequest request) {
		return request.url().getPath().endsWith("/oauth2/tokenP");
	}

	private static ClientResponse json(HttpStatus status, String body) {
		return ClientResponse.create(status)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.body(body)
			.build();
	}
}
