package com.finch.domain.account.controller;

import com.finch.domain.account.dto.response.AccountRes;
import com.finch.domain.account.service.AccountService;
import com.finch.global.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계좌 API (apiSpec 3.1).
 * <p>
 * {@code SecurityConfig} 의 기본이 {@code authenticated()} 라 별도 설정 없이 보호된다.
 * 계좌 식별자를 경로·쿼리로 받지 않는다 — 계좌는 토큰의 사용자로 찾는다 (apiSpec 1.6).
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

	private final AccountService accountService;

	@GetMapping
	public AccountRes getAccount(@LoginUser long userId) {
		return accountService.getSummary(userId);
	}
}
