package com.finch.domain.auth.service;

import com.finch.domain.auth.dto.response.UserMeRes;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * `users` 를 읽는 창구다. auth 가 `users` 를 소유하므로 다른 도메인은 이 서비스를 거친다
 * (backConvention 2.2 규칙 3 — 다른 도메인의 Entity·Repository 를 import 하지 않는다).
 * <p>
 * {@code AuthService} 와 나눈 이유 — 그쪽은 로그인·재발급·로그아웃처럼 <b>토큰을 다루는 흐름</b>이고
 * 이쪽은 사용자 정보 조회다. 다른 도메인이 사용자 정보를 물으러 오는 자리에 카카오 호출과 Redis 가
 * 섞여 있을 이유가 없다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;

	/**
	 * 내 정보 조회 (apiSpec 2.4). userId 는 토큰에서만 온다 — 파라미터로 받는 것은 {@code @LoginUser} 가
	 * 꺼내 준 값이고 컨트롤러가 요청 본문·경로에서 읽은 값이 아니다.
	 * <p>
	 * 사용자가 없을 때 {@code AUTH_INVALID_TOKEN} 을 주는 이유 — 서명은 유효한데 가리키는 계정이
	 * 없는 상태다(탈퇴·DB 초기화). 404 를 주면 프론트가 "경로가 없다" 로 읽어 재시도하지만,
	 * 실제로 필요한 것은 세션을 버리고 다시 로그인하는 것이다. 그 동작을 내는 코드가 이것이다 (apiSpec 1.2).
	 */
	@Transactional(readOnly = true)
	public UserMeRes getMe(long userId) {
		return userRepository.findById(userId)
			.map(UserMeRes::from)
			.orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_INVALID_TOKEN));
	}
}
