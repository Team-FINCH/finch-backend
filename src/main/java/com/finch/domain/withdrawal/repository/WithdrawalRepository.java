package com.finch.domain.withdrawal.repository;

import com.finch.domain.withdrawal.entity.Withdrawal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** `withdrawal` 은 withdrawal 도메인 소유다. 다른 도메인은 이 리포지토리를 import 하지 않는다 (backConvention 2.4 규칙 3). */
public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

	/** 불변식 6(`WITHDRAWAL` 원장 1행 = withdrawal 1행) 대조용이다. 화면용 조회가 아니다 — 내역은 S4 가 DTO 프로젝션으로 만든다. */
	List<Withdrawal> findByAccountIdOrderByIdDesc(Long accountId);

	/** 감사용 합계. 출금 가능액 판정에는 쓰지 않는다 — 판정은 잠근 계좌 스냅샷의 {@code cash_balance} 로 한다. */
	@Query("select coalesce(sum(w.amount), 0) from Withdrawal w where w.accountId = :accountId")
	long sumAmountByAccountId(Long accountId);
}
