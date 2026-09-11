package com.finch.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.dto.request.KakaoLoginReq;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import com.finch.global.security.RefreshTokenStore;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/** 로그인 한 번의 조립이 맞는지 본다. 카카오와 DB 는 흉내내고 토큰만 진짜로 발급한다. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private static final String SECRET = "finch-test-secret-key-0123456789-abcdefgh";

	private static final KakaoUser KAKAO_USER = new KakaoUser(1234567890L, "홍길동", "https://img.kakao/1.jpg");

	private static final KakaoLoginReq REQUEST =
		new KakaoLoginReq("code", "http://localhost:5173/oauth/kakao");

	@Mock
	private KakaoOAuthClient kakaoOAuthClient;

	@Mock
	private UserRepository userRepository;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	/** 가입 트랜잭션은 이 빈이 연다. 여기서는 "불렸는지"만 보고 실제 INSERT 는 통합 테스트가 본다. */
	@Mock
	private UserRegistrationService userRegistrationService;

	/** 계좌 없는 기존 사용자 보정. 신규 가입 경로에서는 불리지 않아야 한다. */
	@Mock
	private AccountService accountService;

	private final JwtProvider jwtProvider = new JwtProvider(SECRET);

	private AuthService authService;

	@BeforeEach
	void setUp() {
		authService = new AuthService(kakaoOAuthClient, userRepository, userRegistrationService, accountService,
			jwtProvider, refreshTokenStore);
	}

	/** 테스트 로그인(apiSpec 2.5)이 쓰는 뒤쪽 절반. 카카오를 부르지 않고, 조회·가입·토큰·Refresh 저장은 카카오 로그인과 같다. */
	@Test
	@DisplayName("loginAs 는 카카오를 부르지 않고 같은 조립으로 토큰을 발급·저장한다")
	void loginAsSkipsKakao() {
		KakaoUser testUser = new KakaoUser(-1L, "테스트 사용자 1", null);
		given(userRepository.findByKakaoId(-1L)).willReturn(Optional.empty());
		given(userRegistrationService.register(testUser)).willReturn(userWithId(5L, "테스트 사용자 1"));

		LoginResult result = authService.loginAs(testUser);

		verifyNoInteractions(kakaoOAuthClient);
		assertThat(result.body().isNewUser()).isTrue();
		assertThat(jwtProvider.parseAccessToken(result.body().accessToken())).isEqualTo(5L);
		verify(refreshTokenStore).save(5L, result.refreshToken());
	}

	@Test
	@DisplayName("처음 보는 kakaoId 면 계정을 만들고 isNewUser 가 true 다")
	void registersOnFirstLogin() {
		given(kakaoOAuthClient.fetchUser(any(), any())).willReturn(KAKAO_USER);
		given(userRepository.findByKakaoId(KAKAO_USER.kakaoId())).willReturn(Optional.empty());
		given(userRegistrationService.register(KAKAO_USER)).willReturn(userWithId(1L, "홍길동"));

		LoginResult result = authService.loginWithKakao(REQUEST);

		assertThat(result.body().isNewUser()).isTrue();
		assertThat(result.body().user().userId()).isEqualTo(1L);
		// 토큰이 진짜 그 사용자를 가리키는지까지 본다. 발급만 되고 다른 id 가 박히면 조용히 남의 계정이 된다.
		assertThat(jwtProvider.parseAccessToken(result.body().accessToken())).isEqualTo(1L);
		assertThat(jwtProvider.parseRefreshToken(result.refreshToken())).isEqualTo(1L);
	}

	/**
	 * 신규 가입은 {@code UserRegistrationService} 안에서 계좌까지 만든다. 여기서 보정까지 부르면
	 * 계좌를 만들었는지 한 번 더 조회하는 낭비이고, 더 나쁘게는 <b>가입 트랜잭션 밖에서</b> 계좌를
	 * 건드리는 경로가 생긴다.
	 */
	@Test
	@DisplayName("신규 가입은 계좌 보정을 타지 않는다 — 가입 트랜잭션이 이미 계좌를 만들었다")
	void doesNotEnsureAccountForNewUser() {
		given(kakaoOAuthClient.fetchUser(any(), any())).willReturn(KAKAO_USER);
		given(userRepository.findByKakaoId(KAKAO_USER.kakaoId())).willReturn(Optional.empty());
		given(userRegistrationService.register(KAKAO_USER)).willReturn(userWithId(1L, "홍길동"));

		authService.loginWithKakao(REQUEST);

		verifyNoInteractions(accountService);
	}

	/**
	 * 계좌 도메인이 생기기 전에 가입한 사용자는 계좌가 없다. 그들에게 {@code GET /account} 는 실패하고
	 * 주문·충전도 되지 않으므로 로그인할 때 보정한다.
	 */
	@Test
	@DisplayName("기존 사용자 로그인은 계좌를 보정한다 — 계좌 도메인 이전에 가입한 사용자를 위해")
	void ensuresAccountForReturningUser() {
		User existing = userWithId(7L, "예전이름");
		given(kakaoOAuthClient.fetchUser(any(), any())).willReturn(KAKAO_USER);
		given(userRepository.findByKakaoId(KAKAO_USER.kakaoId())).willReturn(Optional.of(existing));
		given(userRepository.save(existing)).willReturn(existing);

		authService.loginWithKakao(REQUEST);

		verify(accountService).ensureAccount(7L);
	}

	@Test
	@DisplayName("이미 있는 kakaoId 면 가입하지 않고 프로필만 갱신한다")
	void updatesProfileOnReturningLogin() {
		User existing = userWithId(7L, "예전이름");
		given(kakaoOAuthClient.fetchUser(any(), any())).willReturn(KAKAO_USER);
		given(userRepository.findByKakaoId(KAKAO_USER.kakaoId())).willReturn(Optional.of(existing));
		given(userRepository.save(existing)).willReturn(existing);

		LoginResult result = authService.loginWithKakao(REQUEST);

		assertThat(result.body().isNewUser()).isFalse();
		assertThat(result.body().user().userId()).isEqualTo(7L);
		assertThat(result.body().user().nickname()).isEqualTo("홍길동");
	}

	@Test
	@DisplayName("동시 가입 경합에서 진 요청도 성공하고, isNewUser 는 false 다")
	void losesRegistrationRaceButStillLogsIn() {
		User createdByOther = userWithId(9L, "홍길동");
		given(kakaoOAuthClient.fetchUser(any(), any())).willReturn(KAKAO_USER);
		// 처음엔 없다고 보고, 가입 트랜잭션이 제약에 막히고, 다시 조회하면 남이 만든 행이 있다.
		given(userRepository.findByKakaoId(KAKAO_USER.kakaoId()))
			.willReturn(Optional.empty(), Optional.of(createdByOther));
		given(userRegistrationService.register(KAKAO_USER))
			.willThrow(new DataIntegrityViolationException("uq_users_kakao_id"));

		LoginResult result = authService.loginWithKakao(REQUEST);

		assertThat(result.body().user().userId()).isEqualTo(9L);
		// 이 요청이 만든 계정이 아니다. 진 쪽의 트랜잭션은 users·account 가 함께 롤백됐고,
		// 이긴 쪽이 이미 계좌를 만들어 뒀다. 아래 보정은 그것을 확인만 하고 지나간다.
		assertThat(result.body().isNewUser()).isFalse();
		verify(accountService).ensureAccount(9L);
	}

	@Test
	@DisplayName("카카오 인증이 실패하면 DB 를 건드리지 않고 그대로 올린다")
	void doesNotTouchDatabaseWhenKakaoFails() {
		given(kakaoOAuthClient.fetchUser(any(), any()))
			.willThrow(new CustomException(AuthErrorCode.AUTH_KAKAO_FAILED));

		assertThatThrownBy(() -> authService.loginWithKakao(REQUEST))
			.isInstanceOf(CustomException.class)
			.extracting("errorCode")
			.isEqualTo(AuthErrorCode.AUTH_KAKAO_FAILED);
		verifyNoInteractions(userRepository);
	}

	@Test
	@DisplayName("재로그인에서 프로필이 그대로면 save 는 호출하되 값은 그대로다")
	void keepsProfileWhenUnchanged() {
		User existing = userWithId(3L, "홍길동");
		ReflectionTestUtils.setField(existing, "profileImageUrl", "https://img.kakao/1.jpg");
		given(kakaoOAuthClient.fetchUser(any(), any())).willReturn(KAKAO_USER);
		given(userRepository.findByKakaoId(KAKAO_USER.kakaoId())).willReturn(Optional.of(existing));
		given(userRepository.save(existing)).willReturn(existing);

		LoginResult result = authService.loginWithKakao(REQUEST);

		assertThat(result.body().user().profileImageUrl()).isEqualTo("https://img.kakao/1.jpg");
	}

	@Test
	@DisplayName("저장된 Refresh 와 일치하면 Access 를 새로 주고 Refresh 도 회전한다")
	void rotatesOnRefresh() {
		String oldRefresh = jwtProvider.createRefreshToken(7L);
		given(refreshTokenStore.matches(7L, oldRefresh)).willReturn(true);

		TokenPair tokens = authService.refresh(oldRefresh);

		assertThat(jwtProvider.parseAccessToken(tokens.accessToken())).isEqualTo(7L);
		assertThat(tokens.refreshToken()).isNotEqualTo(oldRefresh);
		// 새 Refresh 가 저장돼야 다음 재발급이 성립한다. 저장을 빠뜨리면 한 번만 되고 끊긴다.
		verify(refreshTokenStore).save(7L, tokens.refreshToken());
	}

	@Test
	@DisplayName("서명이 유효해도 저장된 것과 다르면 거부한다 — 회전 충돌")
	void rejectsRotatedRefreshToken() {
		String stale = jwtProvider.createRefreshToken(7L);
		given(refreshTokenStore.matches(7L, stale)).willReturn(false);

		assertThatThrownBy(() -> authService.refresh(stale))
			.isInstanceOf(CustomException.class)
			.extracting("errorCode")
			.isEqualTo(AuthErrorCode.AUTH_INVALID_TOKEN);
		verify(refreshTokenStore, never()).save(anyLong(), any());
	}

	@Test
	@DisplayName("Access 토큰을 Refresh 자리에 넣으면 저장소를 보기도 전에 거부한다")
	void rejectsAccessTokenOnRefresh() {
		String access = jwtProvider.createAccessToken(7L);

		assertThatThrownBy(() -> authService.refresh(access))
			.isInstanceOf(CustomException.class)
			.extracting("errorCode")
			.isEqualTo(AuthErrorCode.AUTH_INVALID_TOKEN);
		verifyNoInteractions(refreshTokenStore);
	}

	@Test
	@DisplayName("로그아웃은 저장된 Refresh 를 지운다 — 쿠키만 지우면 서버는 계속 재발급해 준다")
	void logoutDeletesStoredToken() {
		authService.logout(7L);

		verify(refreshTokenStore).delete(7L);
	}

	@Test
	@DisplayName("로그인은 Refresh 를 발급과 동시에 저장한다")
	void savesRefreshTokenOnLogin() {
		given(kakaoOAuthClient.fetchUser(any(), any())).willReturn(KAKAO_USER);
		given(userRepository.findByKakaoId(KAKAO_USER.kakaoId())).willReturn(Optional.empty());
		given(userRegistrationService.register(KAKAO_USER)).willReturn(userWithId(1L, "홍길동"));

		LoginResult result = authService.loginWithKakao(REQUEST);

		verify(refreshTokenStore).save(1L, result.refreshToken());
	}

	/** 엔티티에 id 세터가 없다. DB 가 채우는 값이라 테스트에서만 리플렉션으로 넣는다. */
	private User userWithId(long id, String nickname) {
		User user = User.register(KAKAO_USER.kakaoId(), nickname, null);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
