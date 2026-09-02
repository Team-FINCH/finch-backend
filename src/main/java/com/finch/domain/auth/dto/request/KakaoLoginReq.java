package com.finch.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * `POST /api/v1/auth/kakao` 요청 (apiSpec 2.1).
 * <p>
 * `redirectUri` 를 프론트가 보내는 이유 — 카카오는 인가 코드를 발급할 때 쓴 redirect URI 와
 * 토큰 교환 요청의 redirect URI 가 문자 단위로 같은지 검사한다. 서버가 자기 값을 쓰면
 * 로컬(5173)·배포(도메인)에서 값이 갈려 교환이 실패한다.
 */
public record KakaoLoginReq(
	@NotBlank String authorizationCode,
	@NotBlank String redirectUri
) {
}
