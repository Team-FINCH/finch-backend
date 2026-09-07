package com.finch.domain.deposit.repository;

import com.finch.domain.deposit.entity.Deposit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

	/** confirm 재전송(DONE)에서 최초 응답을 되찾는다. {@code payment_id} 가 UNIQUE 라 최대 1건이다. */
	Optional<Deposit> findByPaymentId(Long paymentId);

	/**
	 * 불변식 2 (`account.total_deposited_amount` = `SUM(deposit.amount)`) 대조용이다.
	 * 한도 판정은 이 합계가 아니라 계좌 스냅샷을 쓴다 — 스냅샷이 잠긴 행이고, 이 합계는 그 검증값이다.
	 */
	@Query("select coalesce(sum(d.amount), 0) from Deposit d where d.accountId = :accountId")
	long sumAmountByAccountId(Long accountId);
}
