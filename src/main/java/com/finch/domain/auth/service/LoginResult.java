package com.finch.domain.auth.service;

import com.finch.domain.auth.dto.response.KakaoLoginRes;

/**
 * 서비스가 컨트롤러에 넘기는 결과. 본문과 Refresh Token 을 나눠 담는다.
 * <p>
 * Refresh 를 본문 DTO 에 넣지 않은 것이 의도다 — 넣어 두면 언젠가 그대로 직렬화돼
 * 응답 본문에 실린다. apiSpec 1.2 가 금지하는 것이 정확히 그것이다.
 */
public record LoginResult(KakaoLoginRes body, String refreshToken) {
}
