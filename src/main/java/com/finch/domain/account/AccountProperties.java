package com.finch.domain.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 계좌 정책값 ({@code finch.account}).
 * <p>
 * {@code global/config/FinchProperties} 에 중첩하지 않고 도메인 패키지에 두는 이유 —
 * {@code global} 이 모든 도메인의 설정 모양을 알게 되면 "global 은 도메인을 참조하지 않는다"
 * (backConvention 2.4 규칙 1)와 반대 방향이 된다. 접두사만 공유한다.
 *
 * @param initialCash 계좌 개설 시 지급하는 예수금 (featureSpec 2.2). 숫자를 코드에 박지 않는 이유는
 *                    시연 중에 바꿀 수 있어야 해서가 아니라, <b>이 값이 정책이지 구현이 아니기</b> 때문이다.
 *                    바뀌면 이미 개설된 계좌는 그대로고 이후 개설분만 달라진다 — 원장이 지급 시점의
 *                    금액을 그대로 들고 있어서 과거를 다시 계산할 필요가 없다.
 */
@ConfigurationProperties("finch.account")
public record AccountProperties(@DefaultValue("1000000") long initialCash) {
}
