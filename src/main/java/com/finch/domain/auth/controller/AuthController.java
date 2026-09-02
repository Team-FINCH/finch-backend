package com.finch.domain.auth.controller;

import com.finch.domain.auth.dto.request.KakaoLoginReq;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.domain.auth.dto.response.TokenRes;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.service.AuthService;
import com.finch.domain.auth.service.LoginResult;
import com.finch.domain.auth.service.TokenPair;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import com.finch.global.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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

	/**
	 * 쿠키의 Refresh Token 으로 Access Token 을 재발급한다 (apiSpec 2.2). 인증 불필요, <b>요청 본문 없음.</b>
	 * <p>
	 * 쿠키가 없는 것과 쿠키가 무효한 것을 <b>다른 코드로 가른다.</b> 프론트는 앱 부팅마다 이 API 를 한 번
	 * 부르는데, 최초 방문자(쿠키 없음)에게 무효와 같은 코드를 주면 로그인 화면으로 튕긴다 (apiSpec 2.2).
	 */
	@PostMapping("/refresh")
	public ResponseEntity<TokenRes> refresh(
		@CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_MISSING);
		}

		TokenPair tokens = authService.refresh(refreshToken);

		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
			.body(new TokenRes(tokens.accessToken()));
	}

	/**
	 * 로그아웃 (apiSpec 2.3). <b>Access Token 은 있어야 하고 Refresh 쿠키는 없어도 204 다</b> (apiSpec 11장).
	 * <p>
	 * 지우는 대상은 쿠키가 아니라 <b>서버에 저장된 Refresh</b> 다. 쿠키만 지우면 브라우저에서만 사라지고
	 * 그 값을 가진 누군가는 계속 재발급할 수 있다.
	 * <p>
	 * 토큰 해석은 {@code JwtAuthenticationFilter} 가 이미 했다. Access Token 이 없거나 무효한 요청은
	 * 이 메서드에 닿지 않는다 — {@code SecurityConfig} 의 화이트리스트에 없으므로 401 로 끝난다.
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@LoginUser Long userId) {
		authService.logout(userId);

		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
			.build();
	}

	/** 브라우저 쪽 쿠키도 즉시 지운다. 속성은 발급할 때와 같아야 브라우저가 같은 쿠키로 인식한다. */
	private ResponseCookie expiredRefreshCookie() {
		return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
			.httpOnly(true)
			.secure(true)
			.sameSite("Lax")
			.path(REFRESH_COOKIE_PATH)
			.maxAge(0)
			.build();
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
