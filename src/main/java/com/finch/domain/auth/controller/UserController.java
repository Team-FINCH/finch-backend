package com.finch.domain.auth.controller;

import com.finch.domain.auth.dto.response.UserMeRes;
import com.finch.domain.auth.service.UserService;
import com.finch.global.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 API (apiSpec 2.4). `users` 를 소유한 auth 도메인에 둔다.
 * <p>
 * 인증이 필요한 첫 엔드포인트이고, {@code @LoginUser} 의 사용 예시 역할을 겸한다.
 * 다른 도메인의 컨트롤러도 이 모양을 따른다 — 사용자 식별자를 파라미터로 받고, 토큰을 직접 읽지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;

	/**
	 * 내 정보 조회. 프론트가 앱 부팅 시 세션을 복구하는 데 쓴다 (프론트 contracts C24).
	 * <p>
	 * 경로에 userId 가 없는 것이 핵심이다. `/users/{userId}` 로 만들면 남의 숫자를 적어 넣는 것만으로
	 * 남의 정보가 열린다 — 토큰을 검증해도 서버가 그 값을 쓰지 않으면 의미가 없다 (apiSpec 1.2).
	 */
	@GetMapping("/me")
	public UserMeRes me(@LoginUser Long userId) {
		return userService.getMe(userId);
	}
}
