package com.finch.domain.auth.dto.request;

/**
 * `POST /auth/test-login` 요청 (apiSpec 2.5). Bean Validation 을 붙이지 않는다 — 키를 먼저 대조하고 그다음에 번호를 본다.
 * 키가 틀린 요청이 형식 오류(400)를 받으면 "이 경로가 있다" 는 사실이 드러난다 ({@code TestLoginService} 주석).
 *
 * @param testUserNo 고정 테스트 계정 번호 {@code 1}.
 */
public record TestLoginReq(Integer testUserNo) {
}
