package com.finch.domain.auth.dto.response;

/**
 * `POST /api/v1/auth/refresh` 응답 본문 (apiSpec 2.2). Access Token 하나뿐이다.
 * <p>
 * 새 Refresh Token 도 함께 발급되지만 본문에 실리지 않는다 — `Set-Cookie` 로만 내려간다 (apiSpec 1.2).
 */
public record TokenRes(String accessToken) {
}
