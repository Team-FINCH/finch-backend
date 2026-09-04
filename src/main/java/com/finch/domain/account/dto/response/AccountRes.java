package com.finch.domain.account.dto.response;

import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * `GET /account` 의 응답 (apiSpec 3.1). 주식 잔고 화면 상단 요약이다.
 * <p>
 * <b>모든 값은 서버가 계산한다</b> (featureSpec 9.3). 특히 {@code totalAsset} 을 프론트가 더하게 두지
 * 않는 이유 — 더하는 규칙이 두 곳이 되면 평가금액이 없는 종목을 어떻게 다룰지 같은 판단이 갈린다.
 * <p>
 * 계좌 식별자를 담지 않는다. 계좌는 사용자당 하나라 클라이언트가 지목할 대상이 아니다 (apiSpec 1.6).
 *
 * @param evaluationAmount Σ(보유 수량 × 현재가). portfolio 가 붙기 전에는 0 이다.
 * @param totalAsset       예수금 + 평가금액.
 * @param asOf             시세 기준 시각. 화면에 "갱신 시각"으로 표시된다.
 */
public record AccountRes(long cashBalance, long evaluationAmount, long totalAsset, OffsetDateTime asOf) {

	public static AccountRes of(long cashBalance, long evaluationAmount, Instant asOf) {
		return new AccountRes(cashBalance, evaluationAmount, cashBalance + evaluationAmount,
			KstTime.toResponse(asOf));
	}
}
