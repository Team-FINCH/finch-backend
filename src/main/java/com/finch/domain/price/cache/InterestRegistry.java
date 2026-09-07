package com.finch.domain.price.cache;

import com.finch.domain.price.PriceProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

/**
 * 관심 신호 (apiSpec 5.6 "폴링 선행·폴백 단계 — 무신호").
 * <p>
 * <b>명시적인 구독·해제 신호가 없다.</b> 시세를 물어보는 것 자체가 관심 신호이고, 묻지 않으면 TTL 30초 뒤에 저절로 회수된다.
 * 폴링 중단이 곧 해제다. 공급자는 이 목록만 채운다 — 전 종목을 매초 갱신할 수는 없다.
 * <p>
 * 자료 구조가 둘인 이유. 만료는 개별 키의 TTL 이 맡고({@code price:interest:{code}}), 목록은 SET 이 맡는다
 * ({@code price:interest:index}). SET 원소에는 TTL 을 걸 수 없고, 반대로 키만 두면 목록을 얻으려 {@code SCAN} 을 돌려야 하는데
 * 그건 키 공간 전체를 훑는 명령이라 운영에서 쓸 것이 못 된다. 그래서 <b>읽을 때 둘을 대조해</b> 만료된 원소를 인덱스에서 뺀다.
 */
@Component
@RequiredArgsConstructor
public class InterestRegistry {

	private static final String MARKER_PREFIX = "price:interest:";
	private static final String INDEX_KEY = "price:interest:index";
	private static final byte[] MARKER_VALUE = "1".getBytes(StandardCharsets.UTF_8);

	private final StringRedisTemplate redisTemplate;
	private final PriceProperties properties;

	/**
	 * 이 종목들에 관심이 있다고 알린다. 이미 있으면 수명이 다시 늘어난다.
	 * <p>
	 * 파이프라인으로 보낸다 — 다건 조회가 최대 50건이라 낱개로 보내면 왕복이 50번이다.
	 */
	public void touch(List<String> stockCodes) {
		if (stockCodes.isEmpty()) {
			return;
		}
		long ttlSeconds = Math.max(1, properties.interestTtl().toSeconds());
		// 바이트 명령으로 보낸다. StringRedisConnection 으로 캐스팅하는 관용구는 커넥션이 프록시일 때
		// ClassCastException 이 난다 — 파이프라인 콜백이 받는 것은 원본 커넥션이 아니다.
		redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
			for (String code : stockCodes) {
				connection.stringCommands().set(key(code), MARKER_VALUE, Expiration.seconds(ttlSeconds),
					SetOption.upsert());
			}
			return null;
		});
		redisTemplate.opsForSet().add(INDEX_KEY, stockCodes.toArray(String[]::new));
	}

	/**
	 * 지금 관심이 살아 있는 종목. 공급자가 매 틱 부른다.
	 * <p>
	 * 인덱스에 있지만 마커가 만료된 종목은 <b>여기서 인덱스에서도 지운다.</b> 따로 청소 배치를 두지 않는 이유 — 이 메서드가
	 * 어차피 주기적으로 불리므로 그때 함께 정리하면 되고, 배치를 하나 더 두면 리더 락 같은 것이 또 필요해진다.
	 */
	public Set<String> interested() {
		Set<String> indexed = redisTemplate.opsForSet().members(INDEX_KEY);
		if (indexed == null || indexed.isEmpty()) {
			return Set.of();
		}
		List<String> codes = new ArrayList<>(indexed);
		List<String> markerKeys = new ArrayList<>(codes.size());
		codes.forEach(code -> markerKeys.add(MARKER_PREFIX + code));

		List<String> markers = redisTemplate.opsForValue().multiGet(markerKeys);
		if (markers == null) {
			return Set.of();
		}
		Set<String> alive = new LinkedHashSet<>();
		List<Object> expired = new ArrayList<>();
		for (int i = 0; i < codes.size(); i++) {
			if (i < markers.size() && markers.get(i) != null) {
				alive.add(codes.get(i));
			} else {
				expired.add(codes.get(i));
			}
		}
		if (!expired.isEmpty()) {
			redisTemplate.opsForSet().remove(INDEX_KEY, expired.toArray());
		}
		return alive;
	}

	private static byte[] key(String stockCode) {
		return (MARKER_PREFIX + stockCode).getBytes(StandardCharsets.UTF_8);
	}
}
