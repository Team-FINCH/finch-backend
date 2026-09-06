package com.finch.domain.deposit.dto.request;

import com.finch.domain.deposit.gateway.MockScenario;

/**
 * `POST /deposits/{paymentId}/mock-approve` 요청 (apiSpec 4.3.2). 본문 자체가 선택이고 {@code scenario} 가 없으면
 * SUCCESS 다. 열거값 밖은 JSON 파싱 단계에서 {@code INVALID_REQUEST} 다.
 */
public record MockApproveReq(MockScenario scenario) {

	public MockScenario scenarioOrDefault() {
		return scenario == null ? MockScenario.SUCCESS : scenario;
	}
}
