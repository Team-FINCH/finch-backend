package com.finch.domain.price.feed.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * KIS REST 를 부르는 유일한 지점. 현재가({@code FHKST01010100})와 기간별시세({@code FHKST03010100}) 둘이다.
 * <p>
 * <b>모든 호출은 키를 받는다.</b> 어느 키로 부를지는 호출자({@link KisKeyPool})가 정하고, 여기서는 그 키의 토큰·리미터·메트릭을 쓴다.
 * 키마다 {@link KisRateLimiter} 가 따로 있어 풀의 키가 늘면 초당 수용량이 키 수만큼 는다.
 * <p>
 * 실패 처리 둘이 자동이다.
 * <ul>
 *   <li><b>한도 초과</b> — HTTP 429 또는 본문 {@code msg_cd=EGW00201}("초당 거래건수 초과"; KIS 는 이걸 500 으로도 준다).
 *       {@code Retry-After}(없으면 1초)만큼 기다려 1회 재시도. 그래도 그러면 {@link KisException.Kind#RATE_LIMITED}.</li>
 *   <li><b>토큰 무효·만료</b> — HTTP 401/403 또는 {@code msg_cd=EGW0012x}. 그 키의 토큰만 버리고 재발급해 1회 재시도.</li>
 * </ul>
 * <b>Micrometer 카운터 {@code kis.calls{key,endpoint,outcome}}</b> 가 모든 호출을 센다. S0-1 실측(초당 호출·429 횟수)을 Prometheus 에서
 * 보기 위한 것이고, 키별 태그라 어느 키가 한도에 닿는지 보인다.
 */
@Slf4j
public class KisClient {

	static final String PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
	static final String CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
	static final String TR_PRICE = "FHKST01010100";
	static final String TR_CANDLE = "FHKST03010100";
	private static final DateTimeFormatter KIS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);
	/** 기간별시세는 한 번에 최대 100 봉이다. 1년(약 245 영업일)은 창을 옮겨 가며 세 번 부른다. */
	private static final int CANDLE_PAGE_LIMIT = 100;

	private final WebClient webClient;
	private final KisTokenManager tokenManager;
	private final KisProperties properties;
	private final MeterRegistry meterRegistry;
	private final Clock clock;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final Map<String, KisRateLimiter> limiters = new ConcurrentHashMap<>();

	public KisClient(WebClient.Builder builder, KisTokenManager tokenManager, KisProperties properties,
		MeterRegistry meterRegistry) {
		this(builder, tokenManager, properties, meterRegistry, Clock.systemUTC());
	}

	/** 리미터의 시계를 고정하려는 테스트가 쓴다. */
	public KisClient(WebClient.Builder builder, KisTokenManager tokenManager, KisProperties properties,
		MeterRegistry meterRegistry, Clock clock) {
		this.webClient = builder.baseUrl(properties.baseUrl()).build();
		this.tokenManager = tokenManager;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
	}

	/** 현재가. 캐시에 넣을 값과 종목 마스터를 고칠 값을 함께 준다. */
	public KisQuote currentPrice(KisCredential key, String stockCode) {
		PriceRes res = call(key, "price", TR_PRICE, PRICE_PATH,
			Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", stockCode), PriceRes.class);
		PriceOutput out = res.output();
		if (out == null || out.currentPrice() == null) {
			throw new KisException(KisException.Kind.REJECTED, "KIS 현재가 응답에 output 이 없다 code=" + stockCode);
		}
		boolean suspended = "Y".equalsIgnoreCase(out.tempStop()) || "58".equals(out.statusCode());
		// 장 전이거나 그날 거래가 없으면 KIS 가 시가·고가·저가를 0 으로 준다. 0 은 값이 아니라 "아직 없다" 라서 null 로 바꾼다 —
		// 그대로 두면 진행 중 봉의 저가가 0 이 되어 차트가 바닥까지 늘어난다.
		return new KisQuote(parseLong(out.currentPrice()), parseLongOrNull(out.basePrice()), suspended,
			statusReason(out.statusCode()), parseLongOrNull(out.sessionOpen()), parseLongOrNull(out.sessionHigh()),
			parseLongOrNull(out.sessionLow()), parseVolumeOrNull(out.sessionVolume()));
	}

	/**
	 * 일봉 {@code [from, to]}, 오래된 날부터. KIS 는 최신순 최대 100 봉을 주므로 받은 것 중 가장 오래된 날의 전날을 새 {@code to} 로
	 * 삼아 {@code from} 에 닿거나 빈 응답이 올 때까지 반복한다. 수정주가({@code FID_ORG_ADJ_PRC=0})다.
	 */
	public List<KisCandle> dailyCandles(KisCredential key, String stockCode, LocalDate from, LocalDate to) {
		List<KisCandle> all = new ArrayList<>();
		LocalDate cursor = to;
		while (!cursor.isBefore(from)) {
			CandleRes res = call(key, "candle", TR_CANDLE, CANDLE_PATH, Map.of(
				"FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", stockCode,
				"FID_INPUT_DATE_1", from.format(KIS_DATE), "FID_INPUT_DATE_2", cursor.format(KIS_DATE),
				"FID_PERIOD_DIV_CODE", "D", "FID_ORG_ADJ_PRC", "0"), CandleRes.class);
			List<CandleRow> rows = res.output2() == null ? List.of() : res.output2().stream()
				.filter(r -> r.date() != null && !r.date().isBlank()).toList();
			if (rows.isEmpty()) {
				break;
			}
			LocalDate oldest = null;
			for (CandleRow r : rows) {
				LocalDate date = LocalDate.parse(r.date(), KIS_DATE);
				all.add(new KisCandle(date, parseLong(r.open()), parseLong(r.high()), parseLong(r.low()),
					parseLong(r.close()), parseLong(r.volume())));
				if (oldest == null || date.isBefore(oldest)) {
					oldest = date;
				}
			}
			if (rows.size() < CANDLE_PAGE_LIMIT) {
				break;
			}
			cursor = oldest.minusDays(1);
		}
		all.sort((a, b) -> a.tradeDate().compareTo(b.tradeDate()));
		return all;
	}

	/**
	 * 호출 한 번의 뼈대 — 리미터 → 토큰 → 요청 → 판정. 한도 초과와 토큰 무효는 각각 1회씩만 재시도한다. 두 번째도 같으면 던진다 —
	 * 여기서 계속 기다리면 폴링 한 틱이 무한정 길어진다.
	 */
	private <T extends KisEnvelope> T call(KisCredential key, String endpoint, String trId, String path,
		Map<String, String> params, Class<T> type) {
		boolean retriedRate = false;
		boolean retriedAuth = false;
		while (true) {
			limiterOf(key).acquire();
			Raw raw = exchange(key, endpoint, trId, path, params);

			if (raw.status() == HttpStatus.TOO_MANY_REQUESTS.value() || "EGW00201".equals(raw.msgCd())) {
				count(key, endpoint, "rate_limited");
				if (retriedRate) {
					throw new KisException(KisException.Kind.RATE_LIMITED,
						"KIS 초당 한도 초과가 재시도 뒤에도 계속된다 key=" + key.label() + " endpoint=" + endpoint);
				}
				retriedRate = true;
				sleep(retryAfter(raw.retryAfter()));
				continue;
			}
			if (raw.status() == HttpStatus.UNAUTHORIZED.value() || raw.status() == HttpStatus.FORBIDDEN.value()
				|| (raw.msgCd() != null && raw.msgCd().startsWith("EGW0012"))) {
				count(key, endpoint, "unauthorized");
				if (retriedAuth) {
					throw new KisException(KisException.Kind.UNAUTHORIZED,
						"KIS 토큰이 재발급 뒤에도 거절된다 key=" + key.label() + " msg=" + raw.msgCd());
				}
				retriedAuth = true;
				tokenManager.invalidate(key);
				continue;
			}
			if (raw.status() >= 400 || (raw.rtCd() != null && !"0".equals(raw.rtCd()))) {
				count(key, endpoint, "rejected");
				throw new KisException(KisException.Kind.REJECTED, "KIS 거절 key=" + key.label() + " endpoint=" + endpoint
					+ " status=" + raw.status() + " msg_cd=" + raw.msgCd() + " msg1=" + raw.msg1());
			}
			count(key, endpoint, "ok");
			try {
				return objectMapper.readValue(raw.body(), type);
			} catch (RuntimeException e) {
				throw new KisException(KisException.Kind.REJECTED, "KIS 응답을 읽지 못했다 endpoint=" + endpoint, e);
			}
		}
	}

	/** 상태·헤더·본문을 그대로 받는다. 4xx·5xx 도 예외로 바꾸지 않는다 — 판정은 {@link #call} 이 한다 ({@code WebClientConfig} 주석). */
	private Raw exchange(KisCredential key, String endpoint, String trId, String path, Map<String, String> params) {
		String token = tokenManager.accessToken(key);
		try {
			Raw raw = webClient.get()
				.uri(builder -> {
					builder.path(path);
					params.forEach(builder::queryParam);
					return builder.build();
				})
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("appkey", key.appKey())
				.header("appsecret", key.appSecret())
				.header("tr_id", trId)
				.header("custtype", "P")
				.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
					.map(body -> toRaw(response.statusCode(), response.headers().asHttpHeaders(), body)))
				.block(properties.timeout());
			if (raw == null) {
				throw new KisException(KisException.Kind.UNAVAILABLE, "KIS 응답이 없다 endpoint=" + endpoint);
			}
			return raw;
		} catch (KisException e) {
			throw e;
		} catch (WebClientRequestException | IllegalStateException e) {
			count(key, endpoint, "unavailable");
			throw new KisException(KisException.Kind.UNAVAILABLE, "KIS 연결 실패 endpoint=" + endpoint, e);
		}
	}

	private Raw toRaw(HttpStatusCode status, HttpHeaders headers, String body) {
		String rtCd = null;
		String msgCd = null;
		String msg1 = null;
		try {
			KisEnvelope env = objectMapper.readValue(body, Envelope.class);
			rtCd = env.rtCd();
			msgCd = env.msgCd();
			msg1 = env.msg1();
		} catch (RuntimeException ignored) {
			// JSON 이 아닌 본문(게이트웨이 HTML 등). 상태 코드로만 판정한다.
		}
		return new Raw(status.value(), headers.getFirst(HttpHeaders.RETRY_AFTER), body, rtCd, msgCd, msg1);
	}

	private KisRateLimiter limiterOf(KisCredential key) {
		return limiters.computeIfAbsent(key.label(), k -> new KisRateLimiter(properties.ratePerSecond(), clock));
	}

	private void count(KisCredential key, String endpoint, String outcome) {
		Counter.builder("kis.calls")
			.description("KIS REST 호출 수. S0-1 실측용 — 키별 초당 호출과 429 를 본다")
			.tag("key", key.label()).tag("endpoint", endpoint).tag("outcome", outcome)
			.register(meterRegistry)
			.increment();
	}

	private static Duration retryAfter(String header) {
		if (header == null || header.isBlank()) {
			return DEFAULT_RETRY_AFTER;
		}
		try {
			return Duration.ofSeconds(Math.max(1, Long.parseLong(header.trim())));
		} catch (NumberFormatException e) {
			return DEFAULT_RETRY_AFTER;
		}
	}

	/** 테스트가 덮어 실제로 기다리지 않게 한다. */
	protected void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 종목상태구분코드({@code iscd_stat_cls_code}). 마스터 파일에는 없는 값이라 현재가 응답이 유일한 출처다. */
	static String statusReason(String code) {
		if (code == null) {
			return null;
		}
		return switch (code) {
			case "51" -> "관리종목";
			case "52" -> "투자위험";
			case "53" -> "투자경고";
			case "54" -> "투자주의";
			case "58" -> "거래정지";
			case "59" -> "단기과열";
			default -> null;
		};
	}

	private static long parseLong(String value) {
		return value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
	}

	/** 거래량은 0 이 정상값이다 — 장 전이나 거래 없는 종목이다. 그래서 0 을 버리지 않는다. */
	private static Long parseVolumeOrNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		long parsed = Long.parseLong(value.trim());
		return parsed >= 0 ? parsed : null;
	}

	private static Long parseLongOrNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		long parsed = Long.parseLong(value.trim());
		return parsed > 0 ? parsed : null;
	}

	private record Raw(int status, String retryAfter, String body, String rtCd, String msgCd, String msg1) {
	}

	interface KisEnvelope {

		String rtCd();

		String msgCd();

		String msg1();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Envelope(@JsonProperty("rt_cd") String rtCd, @JsonProperty("msg_cd") String msgCd,
		@JsonProperty("msg1") String msg1) implements KisEnvelope {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PriceRes(@JsonProperty("rt_cd") String rtCd, @JsonProperty("msg_cd") String msgCd,
		@JsonProperty("msg1") String msg1, PriceOutput output) implements KisEnvelope {
	}

	/**
	 * 현재가 output 중 쓰는 필드. {@code stck_prpr} 현재가, {@code stck_sdpr} 기준가(전일 종가), {@code temp_stop_yn} 임시정지,
	 * {@code iscd_stat_cls_code} 종목상태구분. KIS 는 숫자도 문자열로 준다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record PriceOutput(@JsonProperty("stck_prpr") String currentPrice, @JsonProperty("stck_sdpr") String basePrice,
		@JsonProperty("temp_stop_yn") String tempStop, @JsonProperty("iscd_stat_cls_code") String statusCode,
		@JsonProperty("stck_oprc") String sessionOpen, @JsonProperty("stck_hgpr") String sessionHigh,
		@JsonProperty("stck_lwpr") String sessionLow, @JsonProperty("acml_vol") String sessionVolume) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CandleRes(@JsonProperty("rt_cd") String rtCd, @JsonProperty("msg_cd") String msgCd,
		@JsonProperty("msg1") String msg1, List<CandleRow> output2) implements KisEnvelope {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CandleRow(@JsonProperty("stck_bsop_date") String date, @JsonProperty("stck_oprc") String open,
		@JsonProperty("stck_hgpr") String high, @JsonProperty("stck_lwpr") String low,
		@JsonProperty("stck_clpr") String close, @JsonProperty("acml_vol") String volume) {
	}
}
