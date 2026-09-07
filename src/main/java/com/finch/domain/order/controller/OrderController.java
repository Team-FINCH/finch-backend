package com.finch.domain.order.controller;

import com.finch.domain.order.dto.response.OrderAvailableRes;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.service.OrderService;
import com.finch.global.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 API (apiSpec 7장). 계좌 식별자를 받지 않는다 — 계좌는 토큰의 사용자로 찾는다 (apiSpec 1.6).
 * <p>
 * {@code side} 가 열거값 밖이면 공통 계층이 {@code INVALID_REQUEST} 로 답한다 (apiSpec 11.2). 여기서 검사하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

	private final OrderService orderService;

	@GetMapping("/available")
	public OrderAvailableRes available(@LoginUser Long userId, @RequestParam String stockCode,
		@RequestParam OrderSide side) {
		return orderService.available(userId, stockCode, side);
	}
}
