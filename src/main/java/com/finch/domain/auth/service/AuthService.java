package com.finch.domain.auth.service;

import com.finch.domain.auth.dto.request.KakaoLoginReq;
import com.finch.domain.auth.dto.response.AuthUserRes;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
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
 * ⚠️ <b>최초 로그인의 계좌·회차·예수금 생성은 아직 없다.</b> erd.md 3.1 은 `users` INSERT 와 함께
 * `investment_round`·`ledger_entry` 를 한 트랜잭션에서 만들라고 하지만 그 테이블의 소유 도메인
 * (`account`·`ledger`)이 아직 없다. 소유 도메인이 생기면 이 자리에서 그 서비스를 부르고,
 * 그때는 세 INSERT 를 한 트랜잭션으로 묶어야 한다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

	private final KakaoOAuthClient kakaoOAuthClient;

	private final UserRepository userRepository;

	private final JwtProvider jwtProvider;

	public LoginResult loginWithKakao(KakaoLoginReq request) {
		KakaoUser kakaoUser = kakaoOAuthClient.fetchUser(request.authorizationCode(), request.redirectUri());

		Resolved resolved = userRepository.findByKakaoId(kakaoUser.kakaoId())
			.map(existing -> new Resolved(updateProfile(existing, kakaoUser), false))
			.orElseGet(() -> register(kakaoUser));

		User user = resolved.user();
		KakaoLoginRes body = new KakaoLoginRes(
			jwtProvider.createAccessToken(user.getId()),
			resolved.created(),
			AuthUserRes.from(user));

		// TODO 백4: Refresh 를 Redis 에 저장해 회전·무효화가 가능하게 한다. 지금은 쿠키로만 내려간다.
		return new LoginResult(body, jwtProvider.createRefreshToken(user.getId()));
	}

	/** 카카오에서 닉네임·프로필이 바뀌었으면 반영한다. 같은 값이면 변경 감지가 UPDATE 를 만들지 않는다. */
	private User updateProfile(User user, KakaoUser kakaoUser) {
		user.updateProfile(kakaoUser.nickname(), kakaoUser.profileImageUrl());
		return userRepository.save(user);
	}

	private Resolved register(KakaoUser kakaoUser) {
		try {
			return new Resolved(userRepository.save(
				User.register(kakaoUser.kakaoId(), kakaoUser.nickname(), kakaoUser.profileImageUrl())), true);
		} catch (DataIntegrityViolationException e) {
			// 로그인 버튼을 빠르게 두 번 눌러 두 요청이 모두 "없음" 을 본 경우다.
			// `uq_users_kakao_id` 가 둘째를 막았으므로 계정이 2개 생기지는 않는다. 다시 조회해 이어간다.
			// created 는 false 다 — 이 요청이 만든 것이 아니다. 여기서 true 를 주면 나중에
			// 최초 로그인 지급(erd.md 3.1)을 붙였을 때 한 계정에 두 번 지급될 수 있다.
			return new Resolved(userRepository.findByKakaoId(kakaoUser.kakaoId())
				.orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_KAKAO_FAILED)), false);
		}
	}

	/** 조회·가입의 결과. {@code created} 는 <b>이 요청이</b> 계정을 만들었는지다. */
	private record Resolved(User user, boolean created) {
	}
}
