package com.finch.domain.deposit.controller;

import com.finch.domain.deposit.dto.request.DepositConfirmReq;
import com.finch.domain.deposit.dto.request.DepositReadyReq;
import com.finch.domain.deposit.dto.request.MockApproveReq;
import com.finch.domain.deposit.dto.response.DepositConfirmOutcome;
import com.finch.domain.deposit.dto.response.DepositConfirmRes;
import com.finch.domain.deposit.dto.response.DepositLimitRes;
import com.finch.domain.deposit.dto.response.DepositReadyRes;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.service.DepositService;
import com.finch.global.security.LoginUser;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	/**
	 * 카카오 승인 콜백 (apiSpec 4.3.1). <b>무인증</b> — {@code SecurityConfig.PUBLIC_PATHS} 에 있다. 카카오가 사용자의
	 * 브라우저를 여기로 리다이렉트하므로 {@code Authorization} 헤더를 붙일 방법이 없다. 프론트가 부르는 API 가 아니다.
	 * <p>
	 * 응답은 언제나 302 다. 성공·실패 모두 프론트 화면으로 보내고, 사유는 URL 의 {@code code} 다.
	 * {@code pg_token} 은 카카오가 붙여 주는 값이라 없을 리 없지만, 없어도 400 대신 실패 화면으로 보낸다 —
	 * 이 요청의 응답을 읽는 것은 사람의 브라우저다.
	 */
	@GetMapping("/kakao/approval")
	public ResponseEntity<Void> kakaoApproval(@RequestParam("paymentId") Long paymentId,
		@RequestParam(value = "pg_token", required = false) String pgToken) {
		String redirectUrl = depositService.kakaoApproval(paymentId, pgToken);
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
	}

	/** 모의 이체 승인 (apiSpec 4.3.2). JWT 필수, 본인 건만. 본문은 선택이고 {@code scenario} 기본은 SUCCESS 다. */
	@PostMapping("/{paymentId}/mock-approve")
	public MockApproveRes mockApprove(@LoginUser long userId, @PathVariable Long paymentId,
		@RequestBody(required = false) MockApproveReq request) {
		MockApproveReq body = request == null ? new MockApproveReq(null) : request;
		return depositService.mockApprove(userId, paymentId, body.scenarioOrDefault());
	}

	/**
	 * 충전 확정 (apiSpec 4.4). 예수금이 늘어나는 유일한 엔드포인트다.
	 * <p>
	 * 최초 반영은 201, 같은 {@code paymentKey} 재전송은 200 이고 본문은 같다. 새로고침·네트워크 재시도로 이 호출이
	 * 두 번 도착하는 것이 정상 경로라 두 번째를 에러로 답하지 않는다. {@code Idempotency-Key} 헤더는 쓰지 않는다 (§1.4).
	 */
	@PostMapping("/confirm")
	public ResponseEntity<DepositConfirmRes> confirm(@LoginUser long userId,
		@Valid @RequestBody DepositConfirmReq request) {
		DepositConfirmOutcome outcome = depositService.confirm(userId, request.paymentId(), request.paymentKey(),
			request.amount());
		return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(outcome.body());
	}
}
