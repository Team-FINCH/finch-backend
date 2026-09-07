package com.finch.domain.watchlist.dto.request;

/**
 * 관심 종목 정렬 (apiSpec 6.3 {@code sort}, featureSpec 6장). 열거값 밖은 스프링의 파라미터 변환이 {@code INVALID_REQUEST} 로 끊는다.
 * <p>
 * 어디서 정렬하느냐가 값마다 다르다 (erd.md §2.9). {@link #REGISTERED}·{@link #NAME} 은 DB 가, {@link #CHANGE_RATE} 는
 * 애플리케이션이 한다 — 등락률의 기준 값이 Redis 시세 캐시에 있어 DB 가 볼 수 없다.
 */
public enum WatchlistSort {

	/** 기본. 최근 등록순. */
	REGISTERED,
	/** 종목명 오름차순. */
	NAME,
	/** 등락률 내림차순. 시세가 없는 종목은 뒤로 밀린다. */
	CHANGE_RATE
}
