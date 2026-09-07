package com.finch.domain.portfolio.dto.request;

/**
 * 보유 목록 정렬 (apiSpec 8.1 {@code sort}, featureSpec 9.2). 열거값 밖은 스프링의 파라미터 변환이 {@code INVALID_REQUEST} 로 끊는다.
 * <p>
 * <b>둘 다 애플리케이션이 정렬한다.</b> 기준 값이 현재가에서 나오는데 그 값은 Redis 시세 캐시에 있어 DB 가 볼 수 없다
 * (관심 종목의 {@code CHANGE_RATE} 와 같은 사정). 시세가 없는 종목은 뒤로 민다 — 값 없음을 0 으로 취급하면 실제로 0 인 종목과 섞인다.
 */
public enum PortfolioSort {

	/** 기본. 평가금액 내림차순 — 큰 자산이 위다. */
	EVALUATION,
	/** 평가수익률 내림차순. */
	PROFIT_RATE
}
