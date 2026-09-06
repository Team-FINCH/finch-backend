package com.finch.domain.deposit.controller;

import com.finch.domain.deposit.dto.request.DepositReadyReq;
import com.finch.domain.deposit.dto.response.DepositLimitRes;
import com.finch.domain.deposit.dto.response.DepositReadyRes;
import com.finch.domain.deposit.service.DepositService;
import com.finch.global.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 충전 API (apiSpec §4). 계좌 식별자를 받지 않는다 — 계좌는 토큰의 사용자로 찾는다 (apiSpec 1.6).
 * <p>
 * <b>{@code Idempotency-Key} 를 쓰지 않는다.</b> 충전의 멱등 기준은 PG 가 발급한 {@code paymentKey} 다 (apiSpec 1.4).
 * {@code finch.idempotency.paths} 에 이 경로가 없어야 하고, 있으면 ready 가 400 으로 막힌다.
 */
@RestController
@RequestMapping("/api/v1/deposits")
@RequiredArgsConstructor
public class DepositController {

	private final DepositService depositService;

	@GetMapping("/limit")
	public DepositLimitRes getLimit(@LoginUser long userId) {
		return depositService.getLimit(userId);
	}

	@PostMapping("/ready")
	@ResponseStatus(HttpStatus.CREATED)
	public DepositReadyRes ready(@LoginUser long userId, @Valid @RequestBody DepositReadyReq request) {
		return depositService.ready(userId, request.paymentMethod(), request.amount());
	}
}
