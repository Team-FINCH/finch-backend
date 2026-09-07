package com.finch.domain.price.feed.kis;

import java.time.Clock;
import java.time.Instant;

/**
 * 키 하나의 초당 호출 상한. 고정 1초 창에 {@code ratePerSecond} 개까지 통과시키고, 넘치면 창이 바뀔 때까지 <b>기다린다</b>
 * (거절하지 않는다). 폴링 워커가 부르는 자리라 기다리는 것이 맞다 — 거절하면 그 종목만 이번 틱에 빠진다.
 * <p>
 * 토큰 버킷을 쓰지 않은 이유 — KIS 한도가 "초당 N건" 고정 창이라 같은 모양이 맞고, 버킷의 버스트 허용은 오히려 429 를 부른다.
 * 인스턴스 하나(리더)만 호출하므로 로컬 카운터로 충분하다. Redis 카운터는 리더가 둘일 수 없는 지금 구조에서 왕복만 늘린다.
 */
final class KisRateLimiter {

	private final int permitsPerSecond;
	private final Clock clock;
	private long windowSecond = Long.MIN_VALUE;
	private int used;

	KisRateLimiter(int permitsPerSecond, Clock clock) {
		this.permitsPerSecond = Math.max(1, permitsPerSecond);
		this.clock = clock;
	}

	/** 통과할 때까지 막는다. 인터럽트되면 그대로 돌아온다 — 종료 중이라는 뜻이다. */
	synchronized void acquire() {
		while (true) {
			Instant now = Instant.now(clock);
			long second = now.getEpochSecond();
			if (second != windowSecond) {
				windowSecond = second;
				used = 0;
			}
			if (used < permitsPerSecond) {
				used++;
				return;
			}
			long waitMillis = 1000 - (now.toEpochMilli() % 1000);
			try {
				wait(Math.max(1, waitMillis));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
