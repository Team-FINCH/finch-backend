package com.finch.domain.auth.service;

import com.finch.domain.auth.TestLoginProperties;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 테스트 로그인 (apiSpec 2.5). 카카오 대신 <b>공유 키</b>로 신원을 확인하고, 그 뒤는 카카오 로그인과 같은 경로
 * ({@link AuthService#loginAs})를 탄다 — 토큰 발급·Refresh 저장·로그아웃이 전부 같다.
 * <p>
 * <b>설정을 켰을 때만 빈이 생긴다</b> ({@code finch.auth.test-login.enabled=true}). 운영에서도 쓰는 입구라 안전장치가 셋이다.
 * <ol>
 *   <li><b>테스트 계정만.</b> {@code kakaoId} 가 {@code -1} ~ {@code -}{@value #MAX_TEST_USERS} 인 계정으로만 들어간다. 카카오
 *       회원번호는 양수라 실제 회원 계정으로는 이 경로로 들어갈 수 없다.</li>
 *   <li><b>키가 틀리면 404.</b> 꺼져 있을 때와 같은 응답이다 — 키 없이 두드리는 쪽은 경로가 있는지조차 알 수 없다. 그래서 요청 형식
 *       검사도 키 대조 뒤에 한다.</li>
 *   <li><b>키 없이는 켜지지 않는다.</b> 켰는데 키가 비었거나 {@value #MIN_KEY_LENGTH}자보다 짧으면 기동에 실패한다. 빈 키로 켜지면
 *       헤더 없이도 통과할 수 있다.</li>
 * </ol>
 * 쓸 때마다 WARN 을 남긴다 — 운영 로그에서 누가 언제 이 입구를 썼는지 보이게. 키는 찍지 않는다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "finch.auth.test-login.enabled", havingValue = "true")
public class TestLoginService {

	static final int MAX_TEST_USERS = 10;
	static final int MIN_KEY_LENGTH = 16;

	private final AuthService authService;
	private final byte[] key;

	public TestLoginService(AuthService authService, TestLoginProperties properties) {
		String configured = properties.key();
		if (configured == null || configured.isBlank() || configured.strip().length() < MIN_KEY_LENGTH) {
			throw new IllegalStateException("finch.auth.test-login 을 켰는데 키가 없거나 " + MIN_KEY_LENGTH
				+ "자보다 짧다. FINCH_AUTH_TESTLOGIN_KEY 를 넣거나 FINCH_AUTH_TESTLOGIN_ENABLED 를 끈다");
		}
		this.authService = authService;
		this.key = configured.strip().getBytes(StandardCharsets.UTF_8);
		log.warn("테스트 로그인이 켜져 있다 — POST /api/v1/auth/test-login. 발표·평가가 끝나면 끈다");
	}

	/**
	 * @param providedKey 요청 헤더 {@code X-Test-Login-Key}. 없거나 틀리면 404 {@code RESOURCE_NOT_FOUND}.
	 * @param testUserNo  1~{@value #MAX_TEST_USERS}. 밖이면 400 {@code INVALID_REQUEST} — 키가 맞은 뒤에만 이 판정에 닿는다.
	 */
	public LoginResult login(String providedKey, Integer testUserNo) {
		if (providedKey == null || !MessageDigest.isEqual(key, providedKey.strip().getBytes(StandardCharsets.UTF_8))) {
			throw new CustomException(GeneralErrorCode.RESOURCE_NOT_FOUND);
		}
		if (testUserNo == null || testUserNo < 1 || testUserNo > MAX_TEST_USERS) {
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST,
				Map.of("testUserNo", "1~" + MAX_TEST_USERS + " 사이여야 합니다"));
		}
		LoginResult result = authService.loginAs(testUser(testUserNo));
		log.warn("테스트 로그인 사용 testUserNo={} userId={}", testUserNo, result.body().user().userId());
		return result;
	}

	/** 음수 {@code kakaoId} 가 테스트 계정의 표시다. 카카오 회원번호와 겹치지 않는다. 프로필 이미지는 없다. */
	static KakaoUser testUser(int testUserNo) {
		return new KakaoUser(-(long) testUserNo, "테스트 사용자 " + testUserNo, null);
	}
}
