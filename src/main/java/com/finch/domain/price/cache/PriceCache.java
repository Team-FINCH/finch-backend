package com.finch.domain.price.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시세 캐시 (erd.md §1.4 — 휘발성 데이터는 Redis 에 둔다).
 * <p>
 * <b>캐시가 진실이고 공급자는 채우는 쪽이다.</b> API 는 DB 도 외부 API 도 보지 않고 여기만 읽는다. Fake(S7)든 KIS(S10)든
 * 같은 {@link #put} 을 쓰므로, "두 경로의 페이로드 스키마가 같다"(apiSpec 5.6)를 캐시 스키마 하나가 보장한다.
 * <p>
 * <b>TTL 을 두지 않는다.</b> 값이 만료되면 "수신 끊김"(마지막 값 유지 + stale)이 "값 없음"(전부 null)으로 바뀌는데,
 * apiSpec 5.4 는 그 둘을 다른 상태로 정했다. 마지막 값은 다음 수신이 덮을 때까지 남는다. 종목이 3천 개 남짓이라 크기도 문제되지 않는다.
 * <p>
 * <b>키 하나에 JSON 하나이고 해시가 아니다.</b> 다건 조회(apiSpec 5.5, 최대 50건)가 {@code MGET} 한 번으로 끝나기 때문이다 —
 * 해시로 두면 50번의 {@code HGETALL} 이 필요하다. 값은 언제나 통째로 읽고 통째로 쓰므로 필드 단위 접근이 필요 없다.
 * <p>
 * {@code StringRedisTemplate} 을 쓰는 이유 — 값이 redis-cli 로 읽히는 형태여야 시세가 안 맞을 때 캐시를 눈으로 볼 수 있다
 * ({@code RedisConfig} 가 같은 이유를 적어 두었다). 직렬화기를 여기서 따로 두는 것은 <b>클래스 이름을 JSON 에 박지 않기</b> 위해서다 —
 * 박으면 패키지를 옮기는 순간 기존 캐시를 못 읽는다.
 */
@Slf4j
@Component
public class PriceCache {

	private static final String KEY_PREFIX = "price:";

	private final StringRedisTemplate redisTemplate;

	/**
	 * 애플리케이션의 {@code ObjectMapper} 를 주입받지 않는다. 그 매퍼는 HTTP 응답 형식(빈 필드 제외·날짜 표기)에 맞춰져 있고,
	 * 캐시는 넣은 그대로 되받는 것이 목적이라 요구가 다르다. 하나를 공유하면 응답 형식을 고칠 때 캐시 호환성이 조용히 깨진다.
	 */
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	public PriceCache(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void put(String stockCode, PriceEntry entry) {
		redisTemplate.opsForValue().set(key(stockCode), objectMapper.writeValueAsString(entry));
	}

	/**
	 * 값이 없을 때만 넣는다 ({@code SETNX}). 실시간 티어가 접속 직후 REST 로 초기값을 채울 때 쓴다 — 그 사이 체결이 먼저 들어왔으면
	 * 그쪽이 더 새 값이라 덮으면 안 된다. "읽고 없으면 쓴다" 두 번 왕복은 그 틈에 체결이 끼어들 수 있어 원자적 한 번으로 한다.
	 *
	 * @return 넣었으면 true, 이미 값이 있어 건너뛰었으면 false
	 */
	public boolean putIfAbsent(String stockCode, PriceEntry entry) {
		return Boolean.TRUE.equals(
			redisTemplate.opsForValue().setIfAbsent(key(stockCode), objectMapper.writeValueAsString(entry)));
	}

	public Optional<PriceEntry> get(String stockCode) {
		return Optional.ofNullable(read(stockCode, redisTemplate.opsForValue().get(key(stockCode))));
	}

	/**
	 * 여러 종목을 {@code MGET} 한 번으로. <b>돌려주는 맵에는 캐시에 있던 종목만 담긴다</b> — 없는 종목을 어떻게 표현할지는
	 * 호출자(={@code PriceQueryService})가 apiSpec 5.4 의 세 상태로 정한다.
	 */
	public Map<String, PriceEntry> getAll(List<String> stockCodes) {
		Map<String, PriceEntry> result = new LinkedHashMap<>();
		if (stockCodes.isEmpty()) {
			return result;
		}
		List<String> keys = new ArrayList<>(stockCodes.size());
		stockCodes.forEach(code -> keys.add(key(code)));

		List<String> values = redisTemplate.opsForValue().multiGet(keys);
		if (values == null) {
			return result;
		}
		for (int i = 0; i < stockCodes.size() && i < values.size(); i++) {
			PriceEntry entry = read(stockCodes.get(i), values.get(i));
			if (entry != null) {
				result.put(stockCodes.get(i), entry);
			}
		}
		return result;
	}

	/**
	 * 읽지 못한 값은 <b>없는 것으로 본다.</b> 캐시가 깨졌다고 조회 API 가 500 을 내면 그 종목뿐 아니라 목록 전체가 죽는다.
	 * 값 없음은 apiSpec 5.4 가 정의한 정상 상태이고, 다음 수신이 덮어쓴다.
	 */
	private PriceEntry read(String stockCode, String json) {
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, PriceEntry.class);
		} catch (RuntimeException e) {
			log.warn("시세 캐시를 읽지 못했다 — 값 없음으로 본다. stockCode={} value={}", stockCode, json, e);
			return null;
		}
	}

	private static String key(String stockCode) {
		return KEY_PREFIX + stockCode;
	}
}
