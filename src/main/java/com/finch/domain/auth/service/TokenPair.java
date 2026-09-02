package com.finch.domain.auth.service;

/** 서비스가 발급한 토큰 두 개. 컨트롤러가 access 는 본문에, refresh 는 쿠키에 나눠 싣는다. */
public record TokenPair(String accessToken, String refreshToken) {
}
