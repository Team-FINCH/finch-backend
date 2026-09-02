package com.finch.domain.auth.service;

/**
 * 카카오에서 받아 온 사용자 정보. 우리 응답 형식이 아니라 외부 응답을 우리 말로 옮긴 것이다.
 *
 * @param profileImageUrl 선택 동의 항목이라 null 일 수 있다
 */
public record KakaoUser(Long kakaoId, String nickname, String profileImageUrl) {
}
