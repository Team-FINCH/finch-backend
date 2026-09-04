package com.finch.domain.account.port;

import java.time.Instant;

/**
 * 보유 종목의 평가금액을 묻는 창구. <b>{@code account}(2층)가 선언하고 {@code portfolio}(3층)가 구현한다.</b>
 * <p>
 * `GET /account` 의 {@code evaluationAmount}·{@code asOf} 는 보유 종목과 시세에서 나오는데, 그 데이터는
 * portfolio 소유다. account 가 portfolio 를 직접 부르면 <b>아래층이 위층을 참조</b>하게 되어
 * backConvention 2.4 규칙 2 를 어긴다. 그래서 <b>필요한 쪽이 인터페이스를 선언하고 가진 쪽이 구현</b>한다
 * (의존성 역전). 컴파일 의존은 portfolio → account 한 방향이고, 실행 시점 방향만 반대가 된다.
 */
public interface ValuationPort {

	/**
	 * @param accountId 평가 대상 계좌.
	 * @return 평가금액과 그 값이 어느 시점 시세 기준인지.
	 */
	Valuation evaluate(Long accountId);

	/**
	 * @param evaluationAmount Σ(보유 수량 × 현재가) (apiSpec 3.1).
	 * @param asOf             시세 기준 시각. 화면에 "갱신 시각"으로 표시된다.
	 */
	record Valuation(long evaluationAmount, Instant asOf) {
	}
}
