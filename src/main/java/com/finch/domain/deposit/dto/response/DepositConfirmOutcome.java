package com.finch.domain.deposit.dto.response;

/**
 * confirm 의 결과. 본문은 같아도 상태 코드가 다르다 — 최초 반영은 201, 재전송(이미 DONE)은 200 이다 (apiSpec 4.4).
 * 서비스가 "이번에 돈이 움직였는가"를 알고 컨트롤러가 그것을 상태 코드로 옮긴다.
 */
public record DepositConfirmOutcome(DepositConfirmRes body, boolean replayed) {
}
