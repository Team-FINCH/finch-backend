package com.finch.domain.price.cache;

import com.finch.domain.price.MarketIndex;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지수 캐시 (apiSpec 5.7). {@link PriceCache} 와 같은 방식이다 — 키 하나에 JSON 하나, TTL 없음, 읽지 못한 값은 없는 것으로 본다.
 * 이유도 같아서 여기서 다시 적지 않는다.
 * <p>
 * {@code PriceCache} 에 합치지 않은 이유 — 값의 모양이 다르고, 종목 캐시는 종목코드(6자리)를 키로 받는다. 지수를 그 자리에 넣으면
 * {@code price:KOSPI} 같은 키가 종목 키와 같은 이름공간에 섞인다.
 */
@Slf4j
@Component
public class IndexCache {

	private static final String KEY_PREFIX = "index:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	public IndexCache(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void put(MarketIndex index, IndexEntry entry) {
		redisTemplate.opsForValue().set(key(index), objectMapper.writeValueAsString(entry));
	}

	public Optional<IndexEntry> get(MarketIndex index) {
		return Optional.ofNullable(read(index, redisTemplate.opsForValue().get(key(index))));
	}

	/** 전 지수를 {@code MGET} 한 번으로. <b>캐시에 있던 지수만 담긴다</b> — 없는 지수의 모양은 호출자가 정한다. */
	public Map<MarketIndex, IndexEntry> getAll() {
		MarketIndex[] indices = MarketIndex.values();
		List<String> keys = new ArrayList<>(indices.length);
		for (MarketIndex index : indices) {
			keys.add(key(index));
		}
		Map<MarketIndex, IndexEntry> result = new EnumMap<>(MarketIndex.class);
		List<String> values = redisTemplate.opsForValue().multiGet(keys);
		if (values == null) {
			return result;
		}
		for (int i = 0; i < indices.length && i < values.size(); i++) {
			IndexEntry entry = read(indices[i], values.get(i));
			if (entry != null) {
				result.put(indices[i], entry);
			}
		}
		return result;
	}

	private IndexEntry read(MarketIndex index, String json) {
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, IndexEntry.class);
		} catch (RuntimeException e) {
			log.warn("지수 캐시를 읽지 못했다 — 값 없음으로 본다. index={} value={}", index, json, e);
			return null;
		}
	}

	private static String key(MarketIndex index) {
		return KEY_PREFIX + index.name();
	}
}
