package com.finch.domain.price.feed;

import java.util.Set;

/**
 * 실시간 티어가 <b>지금</b> 맡고 있는 종목. 폴링 공급자가 매 틱 물어 그 종목을 순회에서 뺀다 (apiSpec 5.6 두 티어).
 * <p>
 * "지금" 인 이유 — 웹소켓이 끊겨 있으면 빈 집합이라야 폴링이 그 종목을 다시 맡는다. 설정에 적힌 종목 목록이 아니라
 * 세션이 살아 있고 등록이 끝난 상태에서만 값을 준다. 실시간 티어가 없는 구성(fake·realtime.enabled=false)은 {@link #NONE} 이다.
 */
@FunctionalInterface
public interface RealtimeCoverage {

	/** 실시간 티어가 없거나 꺼진 구성. 폴링이 전부 맡는다. */
	RealtimeCoverage NONE = Set::of;

	Set<String> covered();
}
