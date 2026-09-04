package com.finch.global.util;

import com.finch.global.config.FinchProperties;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * 지금이 정규장 시간인지 판정한다. 주문 접수(apiSpec 7.2 1단계)와 시세 수집이 같은 답을 봐야 하므로
 * <b>판정하는 곳을 여기 하나로 둔다.</b>
 * <p>
 * 정규장은 평일 09:00~15:30 (KST) 이고 <b>양 끝을 포함</b>한다. 15:30:00 에 낸 주문은 받고
 * 15:30:01 은 거절한다 — 마감 시각 자체는 아직 장 안이다.
 * <p>
 * <b>공휴일을 다루지 않는다.</b> 휴장일 달력을 어디선가 받아 와야 하는데 그 출처가 MVP 범위 밖이다.
 * 대신 {@code finch.market.always-open} 으로 장 시간 판정을 통째로 끌 수 있게 했다 — 발표(9/28)가
 * 장 마감 뒤일 수 있고, 그때 주문이 전부 막히면 데모가 성립하지 않는다. 운영 기본값은 false 다.
 */
@Component
public class MarketClock {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalTime OPEN = LocalTime.of(9, 0);
	private static final LocalTime CLOSE = LocalTime.of(15, 30);

	private final FinchProperties properties;
	private final Clock clock;

	public MarketClock(FinchProperties properties) {
		this(properties, Clock.system(KST));
	}

	/**
	 * 시각을 고정해 경계값을 확인하려는 테스트가 쓴다.
	 * <p>
	 * {@code Clock} 을 주입받게 만든 이유 — {@code LocalTime.now()} 를 직접 부르면 09:00 경계를
	 * 검증하려고 그 시각까지 기다리거나 정적 메서드를 흉내 내야 한다. 둘 다 테스트를 느리거나
	 * 불안정하게 만든다.
	 */
	public MarketClock(FinchProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public boolean isOpen() {
		if (properties.market().alwaysOpen()) {
			return true;
		}
		LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
		return isWeekday(now.getDayOfWeek()) && isWithinSession(now.toLocalTime());
	}

	private static boolean isWeekday(DayOfWeek day) {
		return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
	}

	private static boolean isWithinSession(LocalTime time) {
		return !time.isBefore(OPEN) && !time.isAfter(CLOSE);
	}
}
