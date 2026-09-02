package com.finch.domain.auth.repository;

import com.finch.domain.auth.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** `users` 는 auth 소유다. 다른 도메인은 이 리포지토리를 import 하지 않고 auth 의 Service 를 거친다 (backConvention 2.2·규칙 3). */
public interface UserRepository extends JpaRepository<User, Long> {

	/**
	 * 카카오 로그인의 첫 단계. 있으면 로그인, 없으면 가입이다 (erd.md §3.1).
	 * <p>
	 * 이 조회와 INSERT 사이는 원자적이지 않다 — 로그인 버튼을 빠르게 두 번 누르면 두 트랜잭션이 모두
	 * "없음" 을 보고 각각 INSERT 를 시도한다. 둘째는 `uq_users_kakao_id` 위반으로 실패하고,
	 * 서비스가 그 예외를 잡아 다시 조회하는 것이 정상 경로다. 방어선은 DB 제약이다.
	 */
	Optional<User> findByKakaoId(Long kakaoId);
}
