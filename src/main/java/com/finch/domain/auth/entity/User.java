package com.finch.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * `users` 테이블 (erd.md §2.1). auth 도메인이 소유한다 (backConvention 2.2).
 * <p>
 * 테이블 이름을 명시한 이유 — 클래스 이름이 User 라 기본 전략대로면 테이블이 `user` 가 되는데
 * `user` 는 PostgreSQL 예약어다. 스키마는 `users` 이므로 여기서 맞춰 준다.
 * <p>
 * 스키마 변경은 Flyway 전용이고 이 엔티티는 `ddl-auto: validate` 의 검사 대상일 뿐이다.
 * 필드를 늘리려면 `V2__*.sql` 이 먼저다 — 여기만 고치면 기동이 실패한다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * 카카오 회원번호. 로그인은 이 값으로 기존 회원을 찾는다.
	 * <p>
	 * `unique = true` 는 문서 목적이다. `validate` 는 유니크 제약을 검사하지 않으므로
	 * 실제 방어선은 스키마의 `uq_users_kakao_id` 이고, 동시 로그인 경합도 그 제약이 막는다.
	 */
	@Column(nullable = false, unique = true)
	private Long kakaoId;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(length = 500)
	private String profileImageUrl;

	/** `GET /users/me` 의 joinedAt (apiSpec 2.4). */
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	/** 최초 로그인에서만 부른다 (erd.md §3.1). 이후 로그인은 조회 후 {@link #updateProfile} 이다. */
	public static User register(Long kakaoId, String nickname, String profileImageUrl) {
		User user = new User();
		user.kakaoId = kakaoId;
		user.nickname = nickname;
		user.profileImageUrl = profileImageUrl;
		return user;
	}

	/**
	 * 카카오에서 받은 프로필로 갱신한다. 값이 그대로면 Hibernate 의 변경 감지가 UPDATE 를 만들지 않으므로
	 * 호출하는 쪽에서 같은지 비교할 필요가 없다.
	 */
	public void updateProfile(String nickname, String profileImageUrl) {
		this.nickname = nickname;
		this.profileImageUrl = profileImageUrl;
	}

	/**
	 * 두 컬럼 모두 NOT NULL 이고 DB 기본값이 없다. 채우는 책임이 애플리케이션에 있다.
	 * <p>
	 * JPA Auditing(`@CreatedDate`)을 쓰지 않은 이유 — `global/config` 에 `@EnableJpaAuditing` 을 새로 놓아야 하고
	 * 그건 전 도메인이 상속하는 결정이다. auth 혼자 정할 일이 아니라서 엔티티 안에서 끝냈다.
	 * 팀이 Auditing 으로 가기로 하면 이 두 콜백을 지우고 갈아끼우면 된다.
	 */
	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}
}
