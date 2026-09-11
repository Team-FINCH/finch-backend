package com.finch.domain.order.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.finch.domain.order.dto.request.OrderReq;
import com.finch.domain.order.dto.response.OrderAvailableRes;
import com.finch.domain.order.dto.response.OrderRes;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.service.OrderService;
import com.finch.global.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 API (apiSpec 7장). 계좌 식별자를 받지 않는다 — 계좌는 토큰의 사용자로 찾는다 (apiSpec 1.6).
 * <p>
 * <b>{@code POST /orders} 는 {@code Idempotency-Key} 가 필수다</b> (apiSpec 1.4). 검사는 이 컨트롤러가 아니라 {@code IdempotencyFilter}
 * 가 한다 — {@code finch.idempotency.paths} 에 이 경로가 있어 헤더가 없으면 여기 닿기 전에 400 이고, 같은 키의 재전송은
 * 컨트롤러를 다시 부르지 않고 최초 응답을 재생한다. 그래서 이 컨트롤러는 항상 201 이다. 경로가 목록에서 빠지면 이 엔드포인트는
 * 조용히 멱등성 없이 열린다; {@code IdempotencyPropertiesTest} 가 그것을 막는다. {@code WithdrawalController} 와 같은 구조다.
 * <p>
 * {@code side} 가 열거값 밖이면 공통 계층이 {@code INVALID_REQUEST} 로 답한다 (apiSpec 11.2). 여기서 검사하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "주문", description = "매수·매도 주문과 주문 가능 수량")
public class OrderController {

	private final OrderService orderService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrderRes place(@LoginUser Long userId, @Valid @RequestBody OrderReq request) {
		return orderService.place(userId, request);
	}

	@GetMapping("/available")
	public OrderAvailableRes available(@LoginUser Long userId, @RequestParam String stockCode,
		@RequestParam OrderSide side) {
		return orderService.available(userId, stockCode, side);
	}
}
