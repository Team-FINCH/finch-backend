package com.finch.domain.account.port;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * {@link ValuationPort} 의 기본 구현. <b>portfolio 도메인이 생기기 전까지</b>만 쓰인다.
 * <p>
 * 포트를 도입하면서 "구현이 아직 없다"는 이유로 {@code null} 을 허용하거나 호출부에 분기를 두면,
 * 그 분기는 구현이 생긴 뒤에도 남는다. 그래서 빈 값을 주는 구현을 두고 호출부는 포트만 안다.
 * <p>
 * ⚠️ <b>portfolio 를 만드는 스토리는 이 파일을 지운다.</b> 지우지 않고 자기 구현을 추가하면 빈이 둘이
 * 되어 {@code NoUniqueBeanDefinitionException} 으로 <b>기동이 실패한다</b> — 조용히 틀린 값을 쓰는 것보다
 * 낫다.
 * <p>
 * {@code @ConditionalOnMissingBean} 으로 자동 교체되게 하려다 <b>되돌렸다.</b> 그 어노테이션은 자동
 * 구성 밖에서는 동작하지 않는다 — 컴포넌트 스캔 시점에 조건이 평가되는데 그때는 빈 목록이 아직
 * 완성되지 않아서, 조건이 맞는데도 <b>빈이 통째로 등록되지 않고</b> 주입 지점이 전부 깨졌다.
 * <p>
 * <b>0 을 돌려주는 것은 "보유 없음"과 구분되지 않는다.</b> 지금은 보유가 생길 경로 자체가 없어서
 * (주문이 없다) 문제가 되지 않지만, portfolio 가 붙기 전에 매매가 생기면 계좌 요약이 조용히
 * 틀린 값을 보여준다. 그 순서는 스토리 배치가 보장한다.
 */
@Component
public class EmptyValuationPort implements ValuationPort {

	@Override
	public Valuation evaluate(Long accountId) {
		// asOf 를 now 로 주는 이유 — 이 값은 "시세 기준 시각"이고, 평가할 보유가 없으면
		// 가장 최근 정보로 답한 것이 맞다. 화면의 갱신 시각이 비어 보이지 않는다.
		return new Valuation(0L, Instant.now(Clock.systemUTC()));
	}
}
