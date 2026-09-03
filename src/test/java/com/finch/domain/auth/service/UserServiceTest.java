package com.finch.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.finch.domain.auth.dto.response.UserMeRes;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.global.exception.CustomException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** `GET /users/me` 가 apiSpec 2.4 의 값을 어디서 가져오는지 고정한다. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	/** 2026-08-25T10:00:00+09:00 — apiSpec 2.4 예시와 같은 시각의 UTC 표현이다. */
	private static final Instant JOINED_AT = Instant.parse("2026-08-25T01:00:00Z");

	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private UserService userService;

	@Test
	@DisplayName("내 정보는 users 한 행에서 그대로 나온다")
	void readsProfileFromUsers() {
		given(userRepository.findById(42L)).willReturn(Optional.of(user()));

		UserMeRes response = userService.getMe(42L);

		assertThat(response.userId()).isEqualTo(42L);
		assertThat(response.nickname()).isEqualTo("홍길동");
		assertThat(response.profileImageUrl()).isEqualTo("https://img.kakao/1.jpg");
	}

	/**
	 * 저장된 값은 {@code Instant}(UTC)인데 계약은 KST 오프셋이다 (apiSpec 1.1).
	 * 변환을 빠뜨리면 `2026-08-25T01:00:00Z` 가 나가고, 문자열을 그대로 보여주는 화면에서 날짜가 밀린다.
	 */
	@Test
	@DisplayName("joinedAt 은 created_at 을 KST 오프셋으로 옮긴 값이다")
	void convertsJoinedAtToKst() {
		given(userRepository.findById(42L)).willReturn(Optional.of(user()));

		assertThat(userService.getMe(42L).joinedAt())
			.isEqualTo(JOINED_AT.atOffset(ZoneOffset.ofHours(9)))
			.hasToString("2026-08-25T10:00+09:00");
	}

	/**
	 * 서명은 유효한데 가리키는 계정이 없는 상태다(탈퇴·DB 초기화). 404 를 주면 프론트가 "경로가 없다" 로
	 * 읽어 재시도하지만, 필요한 동작은 세션을 버리고 다시 로그인하는 것이다 (apiSpec 1.2).
	 */
	@Test
	@DisplayName("토큰의 사용자가 없으면 AUTH_INVALID_TOKEN — 404 가 아니다")
	void treatsMissingUserAsInvalidToken() {
		given(userRepository.findById(42L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getMe(42L))
			.isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode())
			.isEqualTo(AuthErrorCode.AUTH_INVALID_TOKEN);
	}

	/** id 와 created_at 은 각각 DB 시퀀스와 @PrePersist 가 채우므로 테스트에서는 직접 심는다. */
	private static User user() {
		User user = User.register(1234567890L, "홍길동", "https://img.kakao/1.jpg");
		ReflectionTestUtils.setField(user, "id", 42L);
		ReflectionTestUtils.setField(user, "createdAt", JOINED_AT);
		return user;
	}
}
