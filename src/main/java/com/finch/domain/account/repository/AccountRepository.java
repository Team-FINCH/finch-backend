package com.finch.domain.account.repository;

import com.finch.domain.account.entity.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** `account` 는 account 도메인 소유다. 다른 도메인은 {@code AccountService} 를 거친다 (backConvention 2.4 규칙 3). */
public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByUserId(Long userId);

	boolean existsByUserId(Long userId);

	/**
	 * {@code SELECT ... FOR UPDATE}. <b>돈이 움직이는 트랜잭션의 직렬화 지점</b>이다.
	 * <p>
	 * 충전·출금·주문이 전부 이 한 행을 잠그고 들어간다. 계좌 행 하나를 락 대상으로 삼는 이유 —
	 * 한 사용자의 예수금을 건드리는 모든 경로가 같은 행을 지나므로, 그 행만 잠그면 "예수금이 음수가
	 * 되는" 경합이 전부 막힌다. 사용자가 다르면 다른 행이라 서로 기다리지 않는다.
	 * <p>
	 * {@code @Query} 를 직접 쓴 이유 — 메서드 이름 파생 쿼리에 {@code @Lock} 을 붙여도 동작하지만,
	 * 어떤 SQL 이 나가는지가 이름에 가려진다. 락은 눈에 보여야 한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.userId = :userId")
	Optional<Account> findByUserIdForUpdate(Long userId);
}
