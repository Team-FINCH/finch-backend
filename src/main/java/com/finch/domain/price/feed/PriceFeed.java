package com.finch.domain.price.feed;

/**
 * 시세를 캐시에 채우는 쪽. 구현은 {@link FakePriceFeed}(S7)와 KIS 폴링 공급자(S10) 둘이고, {@code finch.price.provider} 가 고른다.
 * <p>
 * <b>소비 측은 이 인터페이스를 모른다.</b> API 는 캐시만 읽으므로 공급자가 바뀌어도 조회 코드가 바뀌지 않는다 — 그것이 이 스토리에서
 * 시세의 소비 계약을 먼저 굳히고 공급자를 뒤로 미룰 수 있었던 이유다.
 * <p>
 * 기동·종료는 {@code SmartLifecycle} 이 맡는다. 구현체가 스케줄러를 직접 들고 있고, 스프링이 컨텍스트를 닫을 때 함께 멈춘다.
 */
public interface PriceFeed {

	/**
	 * 관심 종목 한 바퀴. 스케줄러가 부르고, 테스트는 이 메서드를 직접 부른다.
	 *
	 * @return 이번에 채운 종목 수.
	 */
	int tick();
}
