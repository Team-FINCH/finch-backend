package com.finch.global.idempotency;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 멱등성 키 장부. Redis 에 "이 키를 누가 처리하고 있는가 / 어떤 응답으로 끝났는가"를 적는다 (erd 1.4).
 * <p>
 * 키는 {@code idem:{userId}:{Idempotency-Key}} 다. <b>사용자 식별자를 키에 넣는 것이 핵심</b>인데,
 * 값을 만드는 주체가 클라이언트이기 때문이다 (apiSpec 1.4). 사용자별로 나누지 않으면 남이 만든 UUID 와
 * 우연히 겹쳤을 때 <b>남의 주문 응답을 그대로 받는다.</b> UUID v4 의 충돌 확률이 낮다는 것은 성실한
 * 클라이언트를 전제한 이야기고, 값을 직접 고르는 쪽이 있으면 확률 이야기가 아니게 된다.
 * <p>
 * 값은 JSON 문자열이고 {@code StringRedisTemplate} 으로 넣는다. 객체 직렬화 템플릿을 쓰지 않는 이유 —
 * 멱등성이 의심스러울 때 redis-cli 로 상태와 본문 해시를 눈으로 봐야 하고, 그러려면 값이 읽히는
 * 형태여야 한다. 크기도 작다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

	private static final String KEY_PREFIX = "idem:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final IdempotencyProperties properties;

	/** 장부에 적히는 상태. 이 둘 말고는 없다 — 실패한 처리는 키를 지워 "없음"으로 되돌린다. */
	public enum State {
		IN_PROGRESS, DONE
	}

	/**
	 * 장부의 한 줄.
	 *
	 * @param bodyHash    최초 요청 본문의 SHA-256. 같은 키로 다른 본문이 오는 것을 잡는 유일한 근거다.
	 * @param status      최초 응답의 상태 코드. 재생할 때 그대로 쓴다 (apiSpec 1.4 "동일 상태 코드").
	 * @param contentType 최초 응답의 Content-Type. 없을 수 있다(204).
	 * @param body        최초 응답 본문. 없을 수 있다.
	 */
	public record Snapshot(State state, String bodyHash, Integer status, String contentType, String body) {
	}

	/**
	 * 이 요청이 처리 주체인지 정한다. {@code SET NX} 한 번으로 판정하므로 동시에 도착한 요청 중
	 * <b>정확히 하나만</b> 빈 값을 받는다 — 앱에서 "있는지 보고 없으면 넣는다"로 나누면 그 사이에
	 * 다른 요청이 끼어든다.
	 *
	 * @return 비어 있으면 이 요청이 처리한다. 값이 있으면 다른 요청이 이미 잡았고, 그 장부가 담긴다.
	 */
	public Optional<Snapshot> begin(long userId, String key, String bodyHash) {
		String redisKey = key(userId, key);
		Snapshot inProgress = new Snapshot(State.IN_PROGRESS, bodyHash, null, null, null);

		if (Boolean.TRUE.equals(redisTemplate.opsForValue()
			.setIfAbsent(redisKey, write(inProgress), properties.inProgressTtl()))) {
			return Optional.empty();
		}

		Snapshot existing = read(redisTemplate.opsForValue().get(redisKey));
		// NX 에 실패한 직후 그 키가 만료되면 읽을 값이 없다. 앞선 처리가 끝나지 못했다는 뜻이므로
		// 이 요청이 이어받는 것이 맞다. 한 번만 다시 시도한다 — 무한 재귀로 만들지 않는다.
		return existing != null ? Optional.of(existing) : begin(userId, key, bodyHash);
	}

	/**
	 * 처리가 끝났다. 이후 같은 키·같은 본문으로 오는 요청은 여기 적힌 응답을 그대로 받는다.
	 * TTL 이 "처리 중"의 60초에서 24시간으로 늘어난다.
	 */
	public void complete(long userId, String key, String bodyHash, int status, String contentType, String body) {
		Snapshot done = new Snapshot(State.DONE, bodyHash, status, contentType, body);
		redisTemplate.opsForValue().set(key(userId, key), write(done), properties.doneTtl());
	}

	/**
	 * 장부에서 지워 "처음 보는 키"로 되돌린다. 처리가 서버 잘못으로 끝났을 때만 부른다.
	 * <p>
	 * 남겨 두면 <b>같은 키로는 영영 재시도할 수 없다.</b> 클라이언트는 실패한 클릭을 같은 키로
	 * 다시 보내도록 되어 있는데(apiSpec 1.4), 저장된 5xx 를 재생해 주면 그 재시도가 전부 무의미해진다.
	 */
	public void release(long userId, String key) {
		redisTemplate.delete(key(userId, key));
	}

	private String write(Snapshot snapshot) {
		return objectMapper.writeValueAsString(snapshot);
	}

	private Snapshot read(String json) {
		return json != null ? objectMapper.readValue(json, Snapshot.class) : null;
	}

	private static String key(long userId, String idempotencyKey) {
		return KEY_PREFIX + userId + ":" + idempotencyKey;
	}
}
