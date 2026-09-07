package com.finch.domain.price.feed.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * KIS 접근토큰을 <b>키별로</b> 발급·보관한다.
 * <p>
 * 토큰은 앱키마다 따로다 — 풀에 키가 셋이면 토큰도 셋이다. Redis 키가 {@code kis:token:{label}} 인 이유이고, 그래서 label 이
 * 유일해야 한다 ({@link KisKeyPool}). 앱키 원문은 Redis 키에 넣지 않는다.
 * <p>
 * <b>Redis 에 두는 이유</b> — 리더가 바뀌어도(배포·재시작) 새 리더가 같은 토큰을 이어 쓴다. KIS 는 토큰 재발급을 <b>키당 분당 1회</b>로
 * 막으므로, 인스턴스마다 발급받으면 두 번째 인스턴스가 기동 직후 {@code EGW00133} 을 맞는다. 로컬 캐시는 Redis 왕복을 줄이는 용도다.
 * <p>
 * TTL 은 {@code expires_in − token-refresh-margin} 이다. KIS 토큰은 24시간인데 만료 직전 값을 쓰면 호출 도중 401 이 난다.
 * 그래도 401 이 오면({@link #invalidate}) 그 키만 버리고 1회 재발급한다 — {@code KisClient} 가 그 흐름을 갖는다.
 */
@Slf4j
public class KisTokenManager {

	static final String KEY_PREFIX = "kis:token:";
	private static final String TOKEN_PATH = "/oauth2/tokenP";

	private final WebClient webClient;
	private final StringRedisTemplate redisTemplate;
	private final KisProperties properties;
	private final Map<String, Cached> local = new ConcurrentHashMap<>();

	public KisTokenManager(WebClient.Builder builder, StringRedisTemplate redisTemplate, KisProperties properties) {
		this.webClient = builder.baseUrl(properties.baseUrl()).build();
		this.redisTemplate = redisTemplate;
		this.properties = properties;
	}

	/** 이 키의 유효한 토큰. 로컬 → Redis → 발급 순으로 찾는다. */
	public String accessToken(KisCredential key) {
		Cached cached = local.get(key.label());
		if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
			return cached.token();
		}
		String redisKey = KEY_PREFIX + key.label();
		String fromRedis = redisTemplate.opsForValue().get(redisKey);
		if (fromRedis != null) {
			Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
			local.put(key.label(), new Cached(fromRedis, Instant.now().plusSeconds(ttl == null || ttl < 0 ? 0 : ttl)));
			return fromRedis;
		}
		return issue(key);
	}

	/** 401 을 맞은 키의 토큰을 버린다. 다음 {@link #accessToken} 이 재발급한다. 다른 키는 건드리지 않는다. */
	public void invalidate(KisCredential key) {
		local.remove(key.label());
		redisTemplate.delete(KEY_PREFIX + key.label());
		log.info("KIS 토큰 폐기 key={}", key.label());
	}

	private synchronized String issue(KisCredential key) {
		// 같은 키로 둘이 동시에 들어오면 두 번째는 첫 번째가 넣은 값을 쓴다 — 분당 1회 제한 때문이다.
		String again = redisTemplate.opsForValue().get(KEY_PREFIX + key.label());
		if (again != null) {
			return again;
		}
		TokenRes res;
		try {
			res = webClient.post()
				.uri(TOKEN_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("grant_type", "client_credentials", "appkey", key.appKey(), "appsecret", key.appSecret()))
				.retrieve()
				.onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class).defaultIfEmpty("")
					.map(body -> new KisException(KisException.Kind.UNAUTHORIZED,
						"KIS 토큰 발급 거절 key=" + key.label() + " status=" + response.statusCode() + " body=" + body)))
				.bodyToMono(TokenRes.class)
				.block(properties.timeout());
		} catch (KisException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new KisException(KisException.Kind.UNAVAILABLE, "KIS 토큰 발급 실패 key=" + key.label(), e);
		}
		if (res == null || res.accessToken() == null) {
			throw new KisException(KisException.Kind.REJECTED, "KIS 토큰 응답에 access_token 이 없다 key=" + key.label());
		}
		Duration ttl = Duration.ofSeconds(res.expiresIn() == null ? 86_400 : res.expiresIn())
			.minus(properties.tokenRefreshMargin());
		if (ttl.isNegative() || ttl.isZero()) {
			ttl = Duration.ofMinutes(1);
		}
		redisTemplate.opsForValue().set(KEY_PREFIX + key.label(), res.accessToken(), ttl);
		local.put(key.label(), new Cached(res.accessToken(), Instant.now().plus(ttl)));
		log.info("KIS 토큰 발급 key={} ttl={}", key.label(), ttl);
		return res.accessToken();
	}

	private record Cached(String token, Instant expiresAt) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TokenRes(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") Long expiresIn) {
	}
}
