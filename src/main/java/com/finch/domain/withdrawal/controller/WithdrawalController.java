package com.finch.domain.withdrawal.controller;

import com.finch.domain.withdrawal.dto.request.WithdrawalReq;
import com.finch.domain.withdrawal.dto.response.WithdrawalRes;
import com.finch.domain.withdrawal.service.WithdrawalService;
import com.finch.global.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 출금 API (apiSpec 4.5). 계좌 식별자를 받지 않는다 — 계좌는 토큰의 사용자로 찾는다 (apiSpec 1.6).
 * <p>
 * <b>{@code Idempotency-Key} 가 필수다</b> (apiSpec 1.4). 검사는 이 컨트롤러가 아니라 {@code IdempotencyFilter} 가
 * 한다 — {@code finch.idempotency.paths} 에 이 경로가 있어 헤더가 없으면 여기 닿기 전에 400 이고, 같은 키의
 * 재전송은 컨트롤러를 다시 부르지 않고 최초 응답을 재생한다. 그래서 이 컨트롤러는 항상 201 이다 — 충전 confirm
 * 처럼 "재생이면 200" 갈래가 없다. 경로가 목록에서 빠지면 이 엔드포인트는 조용히 멱등성 없이 열린다;
 * {@code IdempotencyPropertiesTest} 가 그것을 막는다.
 */
@RestController
@RequestMapping("/api/v1/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

	private final WithdrawalService withdrawalService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public WithdrawalRes withdraw(@LoginUser long userId, @Valid @RequestBody WithdrawalReq request) {
		return withdrawalService.withdraw(userId, request.amount());
	}
}
