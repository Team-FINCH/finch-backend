package com.finch.domain.deposit.dto.response;

/** `POST /deposits/{paymentId}/mock-approve` 응답 (apiSpec 4.3.2). 프론트는 이 셋을 그대로 confirm 에 보낸다. */
public record MockApproveRes(Long paymentId, String paymentKey, long amount) {
}
