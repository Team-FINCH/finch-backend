package com.finch.domain.deposit.repository;

import com.finch.domain.deposit.entity.Payment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/**
	 * {@code SELECT ... FOR UPDATE}. confirm 이 <b>계좌보다 먼저</b> 잠그는 행이다 (erd.md §3.3).
	 * <p>
	 * 같은 paymentKey 로 두 요청이 동시에 오면 둘 다 APPROVED 를 보고 두 번 충전하는 것을 막아야 한다.
	 * 결제 건이 계좌보다 좁은 단위라 먼저 잠근다 — 계좌를 먼저 잠그면 같은 사용자의 다른 결제·주문까지
	 * PG 대조가 끝날 때까지 기다린다. 락 순서(payment → account)는 모든 경로에서 같아야 교착이 없다.
	 * <p>
	 * 키가 아니라 id 로 잠그는 이유 — 승인 전(READY) 건은 키가 없어 키로는 찾을 수 없는데, 그 건도
	 * "아직 승인 전" 409 로 답해야 한다 (apiSpec 4.4 판정 2). 키 대조는 잠근 뒤 엔티티가 한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.id = :id")
	Optional<Payment> findByIdForUpdate(Long id);

	/**
	 * 만료된 READY 건을 한 문장으로 FAILED(EXPIRED) 처리한다. 하루 1회 배치라 행을 하나씩 읽어 올릴 이유가 없다.
	 * {@code clearAutomatically} 는 같은 트랜잭션의 영속성 컨텍스트가 옛 상태를 들고 있지 않게 한다.
	 *
	 * @return 정리한 건수. 로그용이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("update Payment p set p.status = com.finch.domain.deposit.entity.PaymentStatus.FAILED,"
		+ " p.failCode = 'EXPIRED'"
		+ " where p.status = com.finch.domain.deposit.entity.PaymentStatus.READY and p.expiresAt < :now")
	int expireReadyBefore(Instant now);
}
