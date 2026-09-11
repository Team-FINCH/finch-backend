package com.finch.domain.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 테스트 로그인 설정 ({@code finch.auth.test-login}, apiSpec 2.5). 카카오 없이 테스트 계정으로 로그인하는 <b>임시 입구</b>다 —
 * 발표·평가 기간에 운영 서버에서도 쓰고, 끝나면 끈다.
 * <p>
 * <b>{@code application.yaml} 에 {@code ${...}} 자리표시자를 두지 않는다.</b> 값은 스프링의 환경변수 바인딩으로 받는다 —
 * {@code FINCH_AUTH_TESTLOGIN_ENABLED} · {@code FINCH_AUTH_TESTLOGIN_KEY}. 자리표시자를 두면 인프라의 환경변수 계약 검사
 * ({@code infra/scripts/check-env-contract.py})가 "운영이 주지 않는 변수" 로 잡는데, 이 값은 운영에서 <b>안 주는 것이 기본</b>이다.
 *
 * @param enabled 켜면 {@code POST /api/v1/auth/test-login} 이 생긴다. <b>기본은 꺼짐</b>이고 꺼져 있으면 경로 자체가 없다(404).
 * @param key     요청 헤더 {@code X-Test-Login-Key} 와 대조할 값. 켰는데 비었거나 짧으면 기동에 실패한다 ({@code TestLoginService}).
 */
@ConfigurationProperties("finch.auth.test-login")
public record TestLoginProperties(
	@DefaultValue("false") boolean enabled,
	String key
) {
}
