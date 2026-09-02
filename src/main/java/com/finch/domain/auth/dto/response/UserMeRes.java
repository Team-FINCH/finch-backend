package com.finch.domain.auth.dto.response;

import com.finch.domain.auth.entity.User;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * `GET /api/v1/users/me` 응답 본문 (apiSpec 2.4).
 * <p>
 * <b>`currentRoundId` 는 항상 null 이다 — 아직.</b> erd.md 3.1 은 최초 로그인 트랜잭션에서
 * `investment_round` 를 만들라고 하지만 그 테이블의 소유 도메인이 아직 없어 회차를 만드는 코드가
 * 없다 ({@code AuthService} 주석 참고). 조회할 행이 없으므로 0 이나 1 같은 값을 채우지 않는다 —
 * 프론트가 그것을 실제 회차 ID 로 믿고 조회에 쓴다. 소유 도메인이 생기면 이 자리에서 그 서비스에 묻는다.
 */
public record UserMeRes(
	Long userId,
	String nickname,
	String profileImageUrl,
	Long currentRoundId,
	OffsetDateTime joinedAt) {

	/**
	 * apiSpec 1.1 은 모든 시각을 <b>KST 오프셋 포함</b> ISO 8601 로 정했다 (`2026-08-25T10:00:00+09:00`).
	 * <p>
	 * {@code Instant} 를 그대로 내보내면 Jackson 이 `2026-08-25T01:00:00Z` 로 쓴다. ISO 8601 이긴 하지만
	 * 오프셋이 Z 라 계약과 다르고, 프론트가 날짜를 문자열 그대로 보여주는 자리에서 하루가 밀린다.
	 * <p>
	 * 시각을 내려보내는 첫 엔드포인트라 여기서 변환한다. 두 번째가 생기면 이 상수는
	 * {@code global/} 의 공용 상수로 올려야 한다 — 도메인마다 각자 적으면 갈라진다.
	 */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public static UserMeRes from(User user) {
		return new UserMeRes(
			user.getId(),
			user.getNickname(),
			user.getProfileImageUrl(),
			null,
			user.getCreatedAt().atZone(KST).toOffsetDateTime());
	}
}
