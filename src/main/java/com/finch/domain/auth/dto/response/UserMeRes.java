package com.finch.domain.auth.dto.response;

import com.finch.domain.auth.entity.User;
import com.finch.global.util.KstTime;
import java.time.OffsetDateTime;

/**
 * `GET /api/v1/users/me` 응답 본문 (apiSpec 2.4).
 * <p>
 * <b>계좌 식별자를 내려보내지 않는다.</b> 투자 회차가 없어지면서 `currentRoundId` 도 함께 빠졌고
 * (GitLab 이슈 #27), 계좌는 사용자당 하나라 클라이언트가 식별자로 지목할 대상이 아니다.
 * 모든 계좌 관련 요청은 토큰의 사용자로 계좌를 찾는다 (apiSpec 1.6).
 */
public record UserMeRes(
	Long userId,
	String nickname,
	String profileImageUrl,
	OffsetDateTime joinedAt) {

	/**
	 * 시각 표기는 {@link KstTime} 하나가 정한다 (apiSpec 1.1 — KST 오프셋 포함 ISO 8601).
	 * <p>
	 * 이 클래스가 시각을 내려보내는 첫 엔드포인트라 원래는 여기에 {@code ZoneId} 상수를 두고
	 * "두 번째가 생기면 global 로 올린다"고 적어 두었다. {@code GET /account} 의 {@code asOf} 가
	 * 두 번째다 — 그래서 옮겼다.
	 */
	public static UserMeRes from(User user) {
		return new UserMeRes(
			user.getId(),
			user.getNickname(),
			user.getProfileImageUrl(),
			KstTime.toResponse(user.getCreatedAt()));
	}
}
