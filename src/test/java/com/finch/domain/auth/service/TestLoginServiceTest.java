package com.finch.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finch.domain.auth.TestLoginProperties;
import com.finch.domain.auth.dto.response.AuthUserRes;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 테스트 로그인의 세 안전장치(apiSpec 2.5) — 키가 틀리면 404, 번호는 키 대조 뒤에 고정값 1, 키 없이 켜지 않는다.
 * 로그인 조립 자체({@code loginAs})는 {@code AuthServiceTest} 가 보고 여기서는 목이다.
 */
class TestLoginServiceTest {

	private static final String KEY = "test-login-key-0123456789";

	private final AuthService authService = mock(AuthService.class);

	@Test
	@DisplayName("키가 맞으면 음수 kakaoId 의 테스트 계정으로 카카오 로그인과 같은 경로를 탄다")
	void logsInAsTestUser() {
		LoginResult expected = new LoginResult(
			new KakaoLoginRes("access", true, new AuthUserRes(7L, "테스트 사용자 1", null)), "refresh");
		given(authService.loginAs(new KakaoUser(-1L, "테스트 사용자 1", null))).willReturn(expected);

		LoginResult result = service().login(KEY, 1);

		assertThat(result).isEqualTo(expected);
		verify(authService).loginAs(new KakaoUser(-1L, "테스트 사용자 1", null));
	}

	/** 꺼져 있을 때(경로 없음)와 같은 응답이다. 키 없이 두드리는 쪽은 경로가 있는지조차 알 수 없다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "wrong-key-0123456789", "test-login-key-012345678"})
	@DisplayName("키가 없거나 틀리면 404 RESOURCE_NOT_FOUND 이고 로그인하지 않는다")
	void rejectsWrongKey(String key) {
		assertThatThrownBy(() -> service().login(key, 1))
			.isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode())
			.isEqualTo(GeneralErrorCode.RESOURCE_NOT_FOUND);
		verifyNoInteractions(authService);
	}

	/** 번호가 틀려도 키가 틀렸으면 404 다 — 형식 오류(400)가 먼저 나가면 경로가 드러난다. */
	@Test
	@DisplayName("키가 틀리면 번호가 잘못돼도 400 이 아니라 404 다")
	void keyIsCheckedBeforeNumber() {
		assertThatThrownBy(() -> service().login("wrong", 99))
			.extracting(e -> ((CustomException) e).getErrorCode())
			.isEqualTo(GeneralErrorCode.RESOURCE_NOT_FOUND);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(ints = {0, -1, 2, 11})
	@DisplayName("키가 맞고 번호가 1이 아니면 400 INVALID_REQUEST, detail 에 testUserNo")
	void rejectsOutOfRangeNumber(Integer testUserNo) {
		assertThatThrownBy(() -> service().login(KEY, testUserNo))
			.isInstanceOf(CustomException.class)
			.satisfies(e -> {
				CustomException ce = (CustomException) e;
				assertThat(ce.getErrorCode()).isEqualTo(GeneralErrorCode.INVALID_REQUEST);
				assertThat(ce.getDetail()).isInstanceOfSatisfying(Map.class, detail -> assertThat(detail).containsKey("testUserNo"));
			});
		verifyNoInteractions(authService);
	}

	/** 빈 키로 켜지면 헤더 없이도 통과할 수 있다. 조용히 뜨지 않고 기동에서 멈춘다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   ", "short-key"})
	@DisplayName("켰는데 키가 없거나 16자보다 짧으면 만들어지지 않는다 — 기동 실패")
	void refusesToStartWithoutKey(String key) {
		assertThatThrownBy(() -> new TestLoginService(authService, new TestLoginProperties(true, key)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("FINCH_AUTH_TESTLOGIN_KEY");
	}

	@Test
	@DisplayName("앞뒤 공백은 키에서 무시한다 — 환경변수에 줄바꿈이 섞여 들어오는 경우")
	void ignoresSurroundingWhitespace() {
		given(authService.loginAs(any())).willReturn(
			new LoginResult(new KakaoLoginRes("a", false, new AuthUserRes(1L, "테스트 사용자 1", null)), "r"));

		new TestLoginService(authService, new TestLoginProperties(true, KEY + "\n")).login(" " + KEY, 1);

		verify(authService).loginAs(new KakaoUser(-1L, "테스트 사용자 1", null));
	}

	private TestLoginService service() {
		return new TestLoginService(authService, new TestLoginProperties(true, KEY));
	}
}
