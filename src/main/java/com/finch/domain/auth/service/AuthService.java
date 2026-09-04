package com.finch.domain.auth.service;

import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.dto.request.KakaoLoginReq;
import com.finch.domain.auth.dto.response.AuthUserRes;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import com.finch.global.security.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 카카오 로그인 한 번의 흐름을 조립한다 — 카카오에 신원 확인 → 회원 조회·가입 → 우리 토큰 발급.
 * <p>
 * <b>메서드에 {@code @Transactional} 을 붙이지 않았다.</b> 이유가 두 개다.
 * <ol>
 *   <li>카카오 HTTP 호출이 트랜잭션 안에 들어가면 카카오가 느린 동안 DB 커넥션을 붙잡는다.
 *       커넥션 풀은 카카오 응답 시간과 무관해야 한다.</li>
 *   <li>동시 가입 경합을 처리할 수 없게 된다. 제약 위반이 난 트랜잭션은 rollback-only 로 표시되므로
 *       같은 트랜잭션 안에서 예외를 잡아 다시 조회해도 커밋에서 다시 실패한다.
 *       트랜잭션 밖에서 잡으면 재조회가 새 트랜잭션으로 나가 정상 동작한다.</li>
 * </ol>
 * 리포지토리 호출은 각각이 자기 트랜잭션이다(Spring Data 기본). 그래서 프로필 갱신은
 * 영속 상태에 의존하지 않고 {@code save} 로 명시한다.
 * <p>
 * <b>계좌·예수금 생성은 {@link UserRegistrationService} 가 한 트랜잭션으로 처리한다</b> (erd.md 3.1).
 * 이 클래스가 트랜잭션을 열지 않는 위 두 이유가 그대로라서, 트랜잭션이 필요한 부분만 그 빈으로 뺐다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

	private final KakaoOAuthClient kakaoOAuthClient;

	private final UserRepository userRepository;

	private final UserRegistrationService userRegistrationService;

	private final AccountService accountService;

	private final JwtProvider jwtProvider;

	private final RefreshTokenStore refreshTokenStore;

	public LoginResult loginWithKakao(KakaoLoginReq request) {
		KakaoUser kakaoUser = kakaoOAuthClient.fetchUser(request.authorizationCode(), request.redirectUri());

		Resolved resolved = userRepository.findByKakaoId(kakaoUser.kakaoId())
			.map(existing -> new Resolved(updateProfile(existing, kakaoUser), false))
			.orElseGet(() -> register(kakaoUser));

		User user = resolved.user();

		// 계좌 도메인이 생기기 전에 가입한 사용자는 계좌가 없다. 그들에게 GET /account 는 404 이고
		// 주문도 충전도 되지 않으므로 로그인할 때 보정한다. 이미 있으면 아무 일도 하지 않는다.
		// 신규 가입은 register() 안에서 이미 만들어졌으므로 여기서 두 번 만들지 않는다.
		if (!resolved.created()) {
			accountService.ensureAccount(user.getId());
		}

		KakaoLoginRes body = new KakaoLoginRes(
			jwtProvider.createAccessToken(user.getId()),
			resolved.created(),
			AuthUserRes.from(user));

		return new LoginResult(body, issueRefreshToken(user.getId()));
	}

	/**
	 * 쿠키의 Refresh Token 으로 Access Token 을 다시 발급한다 (apiSpec 2.2).
	 * <p>
	 * <b>Refresh 도 함께 새로 내려간다(회전).</b> 옛 토큰은 저장소에서 덮어써져 그 즉시 무효가 된다.
	 * 그래서 유출된 토큰을 공격자가 먼저 쓰면 정상 사용자의 다음 재발급이 실패하고, 탈취가 드러난다.
	 * <p>
	 * 실패는 전부 {@code AUTH_INVALID_TOKEN} 이다 — 만료든 서명 불일치든 회전 충돌이든. 쿠키 자체가 없는
	 * 경우만 {@code AUTH_REFRESH_TOKEN_MISSING} 이고 그 판정은 컨트롤러가 한다(쿠키 유무는 HTTP 의 일이다).
	 */
	public TokenPair refresh(String refreshToken) {
		long userId = jwtProvider.parseRefreshToken(refreshToken);

		if (!refreshTokenStore.matches(userId, refreshToken)) {
			// 서명도 맞고 만료도 아닌데 저장된 것과 다르다 = 이미 회전에 쓰였거나 로그아웃된 토큰이다.
			// 서명 검증만으로는 잡을 수 없어서 저장소가 필요하다.
			throw new CustomException(AuthErrorCode.AUTH_INVALID_TOKEN);
		}

		return new TokenPair(jwtProvider.createAccessToken(userId), issueRefreshToken(userId));
	}

	/**
	 * 로그아웃 (apiSpec 2.3). 저장된 Refresh 를 지워 재발급을 막는다.
	 * <p>
	 * Access Token 은 무상태라 서버가 회수할 수 없다. 남은 최대 30분은 그대로 유효하고,
	 * 그 창을 좁히는 것이 Access 를 짧게 잡은 이유다. 로그아웃이 즉시 끊는 것은 <b>재발급 경로</b>다.
	 */
	public void logout(long userId) {
		refreshTokenStore.delete(userId);
	}

	/** 발급과 저장은 항상 같이 일어난다. 저장을 빠뜨리면 그 토큰으로는 재발급이 안 된다. */
	private String issueRefreshToken(long userId) {
		String refreshToken = jwtProvider.createRefreshToken(userId);
		refreshTokenStore.save(userId, refreshToken);
		return refreshToken;
	}

	/** 카카오에서 닉네임·프로필이 바뀌었으면 반영한다. 같은 값이면 변경 감지가 UPDATE 를 만들지 않는다. */
	private User updateProfile(User user, KakaoUser kakaoUser) {
		user.updateProfile(kakaoUser.nickname(), kakaoUser.profileImageUrl());
		return userRepository.save(user);
	}

	/**
	 * 가입. 실제 INSERT 세 개는 {@link UserRegistrationService} 가 한 트랜잭션으로 처리하고,
	 * 여기서는 <b>동시 가입 경합만</b> 다룬다.
	 * <p>
	 * 경합 처리가 이 자리에 남아 있어야 하는 이유는 그대로다 — 제약 위반으로 롤백된 트랜잭션은
	 * 이미 rollback-only 라, 재조회가 <b>트랜잭션 밖</b>에서 새 트랜잭션으로 나가야 한다.
	 */
	private Resolved register(KakaoUser kakaoUser) {
		try {
			return new Resolved(userRegistrationService.register(kakaoUser), true);
		} catch (DataIntegrityViolationException e) {
			// 로그인 버튼을 빠르게 두 번 눌러 두 요청이 모두 "없음" 을 본 경우다.
			// `uq_users_kakao_id` 가 둘째를 막았으므로 계정이 2개 생기지는 않는다. 다시 조회해 이어간다.
			// 진 쪽은 users·account·ledger_entry 세 INSERT 가 함께 롤백됐다.
			//
			// created 는 false 다 — 이 요청이 만든 것이 아니다. 그래서 이 요청은 아래 ensureAccount 를
			// 타지만, 이긴 쪽이 이미 커밋해 계좌가 있으므로 아무 일도 하지 않는다.
			// 초기 지급은 정확히 한 번이다 (불변식 4: 사용자별 account 는 1개).
			return new Resolved(userRepository.findByKakaoId(kakaoUser.kakaoId())
				.orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_KAKAO_FAILED)), false);
		}
	}

	/** 조회·가입의 결과. {@code created} 는 <b>이 요청이</b> 계정을 만들었는지다. */
	private record Resolved(User user, boolean created) {
	}
}
