package com.finch.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.global.config.FinchProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 장 시간 판정의 경계를 못 박는다. 이 값이 흔들리면 주문 접수(apiSpec 7.2 1단계)가 흔들린다.
 * <p>
 * 경계를 굳이 초 단위까지 확인하는 이유 — 09:00 을 "이후"로 쓰느냐 "이상"으로 쓰느냐는 코드에서 한
 * 글자 차이인데, 결과는 <b>개장 순간의 주문이 전부 거절되는 것</b>이다. 그런 종류의 실수는 테스트가
 * 없으면 장중에만 드러난다.
 */
class MarketClockTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@ParameterizedTest(name = "{0} → 열림={1}")
	@DisplayName("정규장은 평일 09:00~15:30 이고 양 끝을 포함한다")
	@CsvSource({
		// 2026-09-07 은 월요일이다.
		"2026-09-07T08:59:59, false",
		"2026-09-07T09:00:00, true",
		"2026-09-07T12:00:00, true",
		"2026-09-07T15:30:00, true",
		"2026-09-07T15:30:01, false",
		"2026-09-07T23:59:59, false",
	})
	void sessionBoundaries(LocalDateTime now, boolean expected) {
		assertThat(marketClock(now, false).isOpen()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} → 열림={1}")
	@DisplayName("주말은 장중 시각이어도 닫혀 있다")
	@CsvSource({
		// 2026-09-05 토요일, 2026-09-06 일요일. 시각은 셋 다 정규장 한가운데다.
		"2026-09-05T12:00:00, false",
		"2026-09-06T12:00:00, false",
		"2026-09-07T12:00:00, true",
	})
	void weekendIsClosed(LocalDateTime now, boolean expected) {
		assertThat(marketClock(now, false).isOpen()).isEqualTo(expected);
	}

	/**
	 * 발표(9/28)가 장 마감 뒤일 수 있다. 이 스위치가 없으면 그 자리에서 매수 시연이 통째로 막힌다.
	 * 운영 기본값은 false 이고, 그것은 {@code application.yaml} 이 못 박는다.
	 */
	@Test
	@DisplayName("always-open 이면 주말 새벽도 열린 것으로 본다 — 시연용 스위치")
	void alwaysOpenIgnoresSchedule() {
		assertThat(marketClock(LocalDateTime.parse("2026-09-06T03:00:00"), true).isOpen()).isTrue();
	}

	@Test
	@DisplayName("판정 기준은 서버 기본 시간대가 아니라 KST 다")
	void judgesInKoreanTime() {
		// 같은 순간을 UTC 시계로 들고 있어도 KST 로는 09:30 이라 장중이다.
		// 서버·컨테이너의 기본 시간대가 UTC 인 배포 환경에서 이 구분이 무너지면 장 시간이 9시간 밀린다.
		Clock utcClock = Clock.fixed(
			ZonedDateTime.of(LocalDateTime.parse("2026-09-07T09:30:00"), KST).toInstant(), ZoneId.of("UTC"));

		assertThat(new MarketClock(properties(false), utcClock).isOpen()).isTrue();
	}

	private static MarketClock marketClock(LocalDateTime nowInKst, boolean alwaysOpen) {
		Clock clock = Clock.fixed(ZonedDateTime.of(nowInKst, KST).toInstant(), KST);
		return new MarketClock(properties(alwaysOpen), clock);
	}

	private static FinchProperties properties(boolean alwaysOpen) {
		return new FinchProperties(
			new FinchProperties.Market(alwaysOpen),
			new FinchProperties.Http(Duration.ofSeconds(3)),
			new FinchProperties.LeaderLock(Duration.ofSeconds(10), Duration.ofSeconds(3)));
	}
}
