package com.finch.domain.auth.dto.response;

/**
 * `POST /api/v1/auth/kakao` 응답 본문 (apiSpec 2.1).
 * <p>
 * **Refresh Token 은 이 본문에 없다.** `Set-Cookie` 로만 내려간다 (apiSpec 1.2).
 * 필드를 추가하려면 프론트의 `KakaoLoginResponseSchema` 와 같이 움직여야 한다.
 */
public record KakaoLoginRes(String accessToken, boolean isNewUser, AuthUserRes user) {
}
