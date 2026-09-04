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
 * @param initialCash 계좌 개설 시 지급하는 예수금 (featureSpec 2.2). <b>기본값은 0 이다</b> — 실제 증권
 *                    서비스가 가입만으로 돈을 주지 않으므로 사용자는 충전부터 하고 시작한다.
 *                    <p>
 *                    값을 지운 것이 아니라 0 으로 둔 이유 — 지급 여부는 <b>정책이지 구현이 아니다.</b>
 *                    시연에서 잔고가 있는 상태로 시작하고 싶으면 이 값만 올리면 되고, 그때는
 *                    {@code INITIAL_GRANT} 원장 행이 다시 기록된다. 코드를 되돌릴 필요가 없다.
 *                    <p>
 *                    바뀌어도 이미 개설된 계좌는 그대로다 — 원장이 지급 시점 금액을 들고 있어서
 *                    과거를 다시 계산할 필요가 없다.
 */
@ConfigurationProperties("finch.account")
public record AccountProperties(@DefaultValue("0") long initialCash) {
}
