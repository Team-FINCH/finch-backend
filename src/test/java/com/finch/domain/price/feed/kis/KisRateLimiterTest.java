package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 키당 초당 상한. 시계를 손으로 움직여 "창이 바뀌면 풀린다" 를 본다 — 실제 1초를 기다리지 않는다. */
class KisRateLimiterTest {

	@Test
	@DisplayName("한 창에서 N 번은 바로 통과하고 N+1 번째는 다음 초까지 기다린다")
	void blocksBeyondPermitsUntilNextSecond() throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T00:00:00.999Z"));
		KisRateLimiter limiter = new KisRateLimiter(2, new SteppingClock(now));

		limiter.acquire();
		limiter.acquire();
		CompletableFuture<Void> third = CompletableFuture.runAsync(limiter::acquire);
		Thread.sleep(50);
		assertThat(third).isNotDone();

		now.set(now.get().plus(Duration.ofMillis(1)));
		third.get(2, TimeUnit.SECONDS);
		assertThat(third).isDone();
	}

	private static final class SteppingClock extends Clock {

		private final AtomicReference<Instant> now;

		private SteppingClock(AtomicReference<Instant> now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}
}
