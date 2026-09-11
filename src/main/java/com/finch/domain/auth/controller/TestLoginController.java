package com.finch.domain.auth.controller;

import com.finch.domain.auth.dto.request.TestLoginReq;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.domain.auth.service.LoginResult;
import com.finch.domain.auth.service.TestLoginService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 테스트 로그인 (apiSpec 2.5). 카카오 로그인과 <b>같은 응답</b>(본문 + Refresh 쿠키)을 준다 — 그래서 프론트의 세션 복구·재발급·
 * 로그아웃이 손대지 않고 동작한다. 규칙은 {@link TestLoginService} 주석에 있다.
 * <p>
 * <b>설정이 꺼져 있으면 이 컨트롤러가 없다</b> — 경로가 매핑되지 않아 404 {@code RESOURCE_NOT_FOUND} 다. 키가 틀렸을 때와 같은 응답이다.
 * <p>
 * Swagger 에 싣지 않는다({@link Hidden}). 운영 Swagger 가 공개라 켜 둔 동안 입구를 광고하게 된다.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finch.auth.test-login.enabled", havingValue = "true")
public class TestLoginController {

	static final String KEY_HEADER = "X-Test-Login-Key";

	private final TestLoginService testLoginService;

	/** 헤더·본문을 필수로 두지 않는다 — 빠졌을 때도 키 대조가 먼저여야 404 로 끝난다(형식 오류 400 이 경로를 드러내지 않게). */
	@PostMapping("/test-login")
	public ResponseEntity<KakaoLoginRes> login(@RequestHeader(name = KEY_HEADER, required = false) String key,
		@RequestBody(required = false) TestLoginReq request) {
		LoginResult result = testLoginService.login(key, request == null ? null : request.testUserNo());

		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, AuthController.refreshCookie(result.refreshToken()).toString())
			.body(result.body());
	}
}
