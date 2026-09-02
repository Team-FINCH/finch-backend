package com.finch.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 을 Redis 에 둔다 (erd.md 1.4). 키는 `refresh:{userId}`, TTL 은 토큰 만료와 같은 14일이다.
 * <p>
 * 테이블이 아니라 Redis 인 이유 — ① 만료가 곧 삭제라 TTL 로 끝나고 정리 배치가 필요 없다.
 * ② 앱 부팅마다 재발급이 호출되므로 조회가 잦은데 원장 DB 를 그 트래픽에 쓰지 않는다.
 * 대가는 Redis 재기동 시 전원 재로그인이고, 가상 자산이라 감수한다 (erd.md 1.4).
 * <p>
 * <b>사용자당 한 개만 저장한다.</b> 회전은 덮어쓰기, 로그아웃은 삭제다. 그래서 재발급으로 새 토큰을
 * 내주는 순간 직전 토큰은 저장된 값과 달라져 무효가 된다 — 그것이 회전이 막아 주는 것이다.
 * 유출된 Refresh 를 공격자가 쓰면 정상 사용자의 다음 재발급이 실패하므로 탈취가 드러난다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

	private static final String KEY_PREFIX = "refresh:";

	private final StringRedisTemplate redisTemplate;

	/** 로그인·재발급에서 부른다. 같은 사용자의 이전 토큰은 덮어써서 무효가 된다. */
	public void save(long userId, String refreshToken) {
		redisTemplate.opsForValue().set(key(userId), refreshToken, JwtProvider.REFRESH_TOKEN_TTL);
	}

	/**
	 * 제시된 토큰이 지금 유효한 그 토큰인지 본다.
	 * <p>
	 * 서명이 맞고 만료도 아닌데 이 검사에서 걸리는 경우가 <b>회전 충돌</b>이다 — 이미 재발급에 쓰인
	 * 옛 토큰이거나 로그아웃으로 지워진 뒤다. 서명 검증만으로는 이걸 잡을 수 없어서 저장소가 필요하다.
	 */
	public boolean matches(long userId, String refreshToken) {
		return refreshToken.equals(redisTemplate.opsForValue().get(key(userId)));
	}

	/** 로그아웃. 키가 없어도 오류가 아니다 — 이미 없는 상태가 원하는 결과다. */
	public void delete(long userId) {
		redisTemplate.delete(key(userId));
	}

	private String key(long userId) {
		return KEY_PREFIX + userId;
	}
}
