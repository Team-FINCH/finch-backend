package com.finch.domain.price.feed;

/**
 * 지수 캐시를 채우는 쪽 (apiSpec 5.7). 구현은 {@link FakeIndexFeed} 와 {@code KisIndexFeed} 이고 {@code finch.price.provider} 가 고른다.
 * <p>
 * {@link PriceFeed} 와 따로 둔 이유 — 그쪽은 관심 종목을 순회하고 주기가 3초인데, 지수는 관심 신호 없이 두 개를 10초마다 채운다.
 * 한 틱에 섞으면 주기를 따로 가져갈 수 없다.
 */
public interface IndexFeed {

	/**
	 * 전 지수 한 바퀴. 스케줄러가 부르고, 테스트는 이 메서드를 직접 부른다.
	 *
	 * @return 이번에 채운 지수 수.
	 */
	int tick();
}
