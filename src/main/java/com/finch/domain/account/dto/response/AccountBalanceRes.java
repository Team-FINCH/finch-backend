package com.finch.domain.account.dto.response;

import com.finch.domain.account.entity.Account;

/**
 * 계좌의 금액 스냅샷. <b>다른 도메인(deposit·withdrawal·order)이 계좌를 읽는 유일한 모양</b>이다.
 * <p>
 * {@code Account} 엔티티를 밖으로 내보내지 않는 이유 — 다른 도메인의 Entity 를 import 하지 않는 것이 규칙이고
 * (backConvention 2.4 규칙 3), 엔티티를 넘기면 받은 쪽이 {@code applyBalance} 를 직접 불러 원장 없이 잔고를
 * 고칠 수 있는 통로가 생긴다. 잔고 갱신은 {@code AccountService} 의 메서드로만 한다.
 * <p>
 * API 응답이 아니다. {@code accountId} 가 들어 있는 것은 상세 테이블(payment·deposit)의 FK 로 쓰기 위해서이고,
 * 클라이언트에는 나가지 않는다 (apiSpec 1.6).
 *
 * @param accountId            payment·deposit 의 {@code account_id}.
 * @param cashBalance          예수금 (불변식 1 의 스냅샷).
 * @param totalDepositedAmount 계좌 평생 누적 충전액 (불변식 2 의 스냅샷). 충전 한도의 기준이다.
 */
public record AccountBalanceRes(Long accountId, long cashBalance, long totalDepositedAmount) {

	public static AccountBalanceRes from(Account account) {
		return new AccountBalanceRes(account.getId(), account.getCashBalance(), account.getTotalDepositedAmount());
	}
}
