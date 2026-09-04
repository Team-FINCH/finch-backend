package com.finch.domain.account.port;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * {@link ValuationPort} 의 기본 구현. <b>portfolio 도메인이 생기기 전까지</b>만 쓰인다.
 * <p>
 * {@link ConditionalOnMissingBean} 이라 portfolio 가 자기 구현을 빈으로 등록하는 순간 이 빈은
 * 만들어지지 않는다. 포트를 도입하면서 "구현이 아직 없다"는 이유로 {@code null} 을 허용하거나
 * 호출부에 분기를 두면, 그 분기는 구현이 생긴 뒤에도 남는다.
 * <p>
 * ⚠️ <b>portfolio 를 만드는 스토리는 이 클래스를 지운다.</b> {@code @ConditionalOnMissingBean} 은
 * 자동 구성 밖에서는 평가 순서가 보장되지 않는다 — 스캔 순서에 따라 두 빈이 함께 등록되면
 * 주입 지점에서 {@code NoUniqueBeanDefinitionException} 으로 <b>기동이 실패</b>한다. 조용히 틀리는
 * 대신 시끄럽게 죽는 쪽이라 그대로 두지만, 조건에 기대지 말고 파일을 지우는 것이 맞다.
 * <p>
 * <b>0 을 돌려주는 것은 "보유 없음"과 구분되지 않는다.</b> 지금은 보유가 생길 경로 자체가 없어서
 * (주문이 없다) 문제가 되지 않지만, portfolio 가 붙기 전에 매매가 생기면 계좌 요약이 조용히
 * 틀린 값을 보여준다. 그 순서는 스토리 배치가 보장한다.
 */
@Component
@ConditionalOnMissingBean(ValuationPort.class)
public class EmptyValuationPort implements ValuationPort {

	@Override
	public Valuation evaluate(Long accountId) {
		// asOf 를 now 로 주는 이유 — 이 값은 "시세 기준 시각"이고, 평가할 보유가 없으면
		// 가장 최근 정보로 답한 것이 맞다. 화면의 갱신 시각이 비어 보이지 않는다.
		return new Valuation(0L, Instant.now(Clock.systemUTC()));
	}
}
