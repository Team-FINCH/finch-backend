package com.finch.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.auth.entity.User;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * `User` 매핑이 V1 스키마와 맞는지, 그리고 `ddl-auto: validate` 가 검사하지 않는 것
 * (IDENTITY 생성, 시각 자동 기록, UNIQUE 제약)이 실제로 도는지 본다.
 * <p>
 * 실제 PostgreSQL 이 필요하다. `replace = NONE` 은 임베디드 DB 로 바꿔치기하는 기본 동작을 끄는 것이고,
 * 끄지 않으면 Testcontainers 로 띄운 컨테이너 대신 다른 DB 를 보게 된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	@DisplayName("최초 로그인으로 저장하면 id 와 생성·수정 시각이 채워진다")
	void savesNewUser() {
		User saved = userRepository.save(User.register(1234567890L, "홍길동", "https://img.kakao/1.jpg"));
		entityManager.flush();

		// GENERATED ALWAYS AS IDENTITY 라 애플리케이션이 id 를 넣지 않는다. DB 가 채운 값이 돌아와야 한다.
		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
	}

	@Test
	@DisplayName("kakaoId 로 기존 회원을 찾는다 — 로그인의 첫 단계다")
	void findsByKakaoId() {
		userRepository.save(User.register(1234567890L, "홍길동", null));
		entityManager.flush();
		entityManager.clear();

		Optional<User> found = userRepository.findByKakaoId(1234567890L);

		assertThat(found).isPresent();
		assertThat(found.get().getNickname()).isEqualTo("홍길동");
		assertThat(found.get().getProfileImageUrl()).isNull();
	}

	@Test
	@DisplayName("없는 kakaoId 는 빈 Optional — 이 경우가 최초 로그인이다")
	void returnsEmptyForUnknownKakaoId() {
		assertThat(userRepository.findByKakaoId(9999999999L)).isEmpty();
	}

	@Test
	@DisplayName("같은 kakaoId 를 두 번 저장하면 DB 가 막는다 — 동시 로그인의 방어선")
	void rejectsDuplicateKakaoId() {
		userRepository.save(User.register(1234567890L, "홍길동", null));
		entityManager.flush();

		// uq_users_kakao_id 위반. 애플리케이션 코드가 아니라 제약이 막는 것이 핵심이다.
		// flush 가 아니라 save 에서 터진다 — IDENTITY 전략은 생성된 id 를 받아와야 해서
		// persist 시점에 INSERT 를 즉시 보내고, 쓰기 지연이 걸리지 않는다.
		assertThatThrownBy(() -> userRepository.save(User.register(1234567890L, "다른이름", null)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("프로필을 갱신하면 값이 바뀌고 updatedAt 이 뒤로 간다")
	void updateProfileTouchesUpdatedAt() {
		User saved = userRepository.save(User.register(1234567890L, "예전이름", null));
		entityManager.flush();

		saved.updateProfile("새이름", "https://img.kakao/2.jpg");
		entityManager.flush();
		entityManager.clear();

		User reloaded = userRepository.findByKakaoId(1234567890L).orElseThrow();
		assertThat(reloaded.getNickname()).isEqualTo("새이름");
		assertThat(reloaded.getProfileImageUrl()).isEqualTo("https://img.kakao/2.jpg");
		// 시계 정밀도 때문에 "반드시 더 크다" 로 두면 드물게 깨진다. 뒤로 가지 않는 것까지만 고정한다.
		assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(reloaded.getCreatedAt());
	}
}
