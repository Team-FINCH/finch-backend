package com.finch.domain.ledger.repository;

import com.finch.domain.ledger.entity.LedgerEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ⚠️ <b>이 리포지토리를 {@code domain/ledger} 밖에서 부르지 않는다</b> (backConvention 2.5).
 * 원장을 기록하는 도메인(account·deposit·order)은 전부 {@code LedgerService} 를 거친다.
 * <p>
 * 이유 — 원장 불변성과 "기록 직후 잔고"({@code cashBalanceAfter})를 지킬 지점이 한 곳이어야 한다.
 * 리포지토리를 직접 부르는 코드가 하나 생기면 그 자리에서 규칙이 갈라지고, 원장은 <b>고쳐 쓸 수 없는
 * 시계열</b>이라 잘못 들어간 행을 지울 수도 없다.
 * <p>
 * {@code JpaRepository} 가 {@code delete*} 를 상속으로 노출하는 것은 어쩔 수 없다. 그래서 접근 자체를
 * 패키지 밖으로 내보내지 않는 것이 방어선이고, DB 계정에서 UPDATE·DELETE 권한을 회수하는 것이
 * 최종 방어선이다 (erd.md §2.3).
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	/**
	 * 불변식 1(`account.cash_balance` = `SUM(cash_delta)`) 대조와 계좌 개설 검증에 쓴다.
	 * 화면용 조회가 아니다 — `GET /transactions` 는 DTO 프로젝션으로 따로 만든다 (backConvention 2.4 규칙 4).
	 */
	List<LedgerEntry> findByAccountIdOrderByIdDesc(Long accountId);
}
