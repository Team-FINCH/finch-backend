package com.finch.domain.auth.controller;

import com.finch.domain.auth.dto.request.KakaoLoginReq;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.domain.auth.service.AuthService;
import com.finch.domain.auth.service.LoginResult;
import com.finch.global.security.JwtProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API (apiSpec 2장). 경로 접두 `/api/v1` 은 컨트롤러가 직접 적는다 — 서블릿 context-path 를 쓰지 않는다. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	/**
	 * Refresh 쿠키의 경로. `/api/v1/auth` 밖의 요청에는 아예 실리지 않는다.
	 * 일반 API 요청마다 14일짜리 토큰이 따라다니지 않게 하는 것이 목적이다 (apiSpec 2.1).
	 */
	static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

	static final String REFRESH_COOKIE_NAME = "refreshToken";

	private final AuthService authService;

	/**
	 * 카카오 인가 코드로 로그인한다 (apiSpec 2.1). 인증 불필요.
	 * <p>
	 * Refresh Token 은 응답 본문이 아니라 `Set-Cookie` 로만 나간다. HttpOnly 라 JS 가 읽을 수 없고,
	 * 그래서 XSS 로 새지 않는다.
	 */
	@PostMapping("/kakao")
	public ResponseEntity<KakaoLoginRes> loginWithKakao(@Valid @RequestBody KakaoLoginReq request) {
		LoginResult result = authService.loginWithKakao(request);

		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
			.body(result.body());
	}

	private ResponseCookie refreshCookie(String refreshToken) {
		return ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
			.httpOnly(true)
			// 배포는 HTTPS 다. 로컬 http://localhost 는 브라우저가 보안 컨텍스트로 취급해 Secure 쿠키를 그대로 받는다.
			.secure(true)
			// Lax — 카카오에서 돌아오는 것은 top-level 이동이라 쿠키가 실린다. None 은 CSRF 노출을 넓히므로 쓰지 않는다.
			.sameSite("Lax")
			.path(REFRESH_COOKIE_PATH)
			.maxAge(JwtProvider.REFRESH_TOKEN_TTL)
			.build();
	}
}
