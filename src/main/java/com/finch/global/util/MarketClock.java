package com.finch.global.util;

import com.finch.global.config.FinchProperties;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
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
	/** KRX 애프터마켓 (2026-09-14 도입). 시세 세션 판정({@link #sessionNow})에만 쓴다 — 주문 접수({@link #isOpen})는 정규장만이다. */
	private static final LocalTime AFTER_OPEN = LocalTime.of(16, 0);
	private static final LocalTime AFTER_CLOSE = LocalTime.of(20, 0);
	/** 하루 안에서 세션이 바뀌는 시각들, 순서대로. {@link #nextChangeAt} 이 훑는다. */
	private static final List<LocalTime> BOUNDARIES = List.of(OPEN, CLOSE, AFTER_OPEN, AFTER_CLOSE);

	private final FinchProperties properties;
	private final Clock clock;

	/**
	 * 생성자가 둘이라 스프링에게 어느 쪽인지 알려 줘야 한다. 표시가 없으면 스프링은 기본 생성자를
	 * 찾다가 {@code NoSuchMethodException} 으로 기동에 실패한다.
	 */
	@Autowired
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

	/**
	 * 시세가 살아 움직이는 구간 (apiSpec 5.8). 정규장에 더해 <b>KRX 애프터마켓(16:00~20:00, 2026-09-14 도입)</b>도 체결이 이어진다 —
	 * 웹소켓 티어가 그 체결을 그대로 받으므로 프론트는 이 동안 시세를 계속 물어야 한다. {@link #isOpen()}(주문 접수)과는 다른
	 * 질문이다: 애프터마켓에 주문을 받을지는 별개 결정이고, 지금은 정규장만 받는다.
	 * {@code always-open} 이면 {@link Session#REGULAR} — 시연 중에는 시세도 주문도 항상 살아 있는 것으로 본다.
	 */
	public Session sessionNow() {
		if (properties.market().alwaysOpen()) {
			return Session.REGULAR;
		}
		LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
		if (!isWeekday(now.getDayOfWeek())) {
			return Session.CLOSED;
		}
		LocalTime time = now.toLocalTime();
		if (isWithinSession(time)) {
			return Session.REGULAR;
		}
		if (!time.isBefore(AFTER_OPEN) && !time.isAfter(AFTER_CLOSE)) {
			return Session.AFTER;
		}
		return Session.CLOSED;
	}

	/**
	 * 세션이 다음에 바뀌는 시각 (apiSpec 5.8). 프론트가 이때까지 폴링을 멈추거나 이때 다시 상태를 묻는다.
	 * 경계 시각 자체는 아직 이전 세션이다 — 15:30:00 은 정규장이고, 15:30 을 돌려받은 프론트가 그 뒤에 다시 물으면 CLOSED 다.
	 * 주말은 다음 평일 09:00 이다. {@code always-open} 이면 바뀔 일이 없어 null 이다.
	 */
	public Instant nextChangeAt() {
		if (properties.market().alwaysOpen()) {
			return null;
		}
		LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
		for (int dayOffset = 0; dayOffset <= 7; dayOffset++) {
			LocalDateTime day = now.toLocalDate().plusDays(dayOffset).atStartOfDay();
			if (!isWeekday(day.getDayOfWeek())) {
				continue;
			}
			for (LocalTime boundary : BOUNDARIES) {
				LocalDateTime candidate = day.with(boundary);
				if (candidate.isAfter(now)) {
					return candidate.atZone(KST).toInstant();
				}
			}
		}
		throw new IllegalStateException("다음 세션 경계를 찾지 못했다 — 평일이 8일 안에 없을 수 없다");
	}

	private static boolean isWeekday(DayOfWeek day) {
		return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
	}

	private static boolean isWithinSession(LocalTime time) {
		return !time.isBefore(OPEN) && !time.isAfter(CLOSE);
	}

	/** 하루의 시세 세션. 주문 가능 여부가 아니다 — 그것은 {@link #isOpen()} 이다. */
	public enum Session {
		/** 정규장 09:00~15:30. 주문·시세 모두 살아 있다. */
		REGULAR,
		/** 애프터마켓 16:00~20:00. 시세는 살아 있고 주문은 (지금은) 받지 않는다. */
		AFTER,
		/** 그 밖. 체결이 없어 마지막 값이 곧 현재가다. */
		CLOSED
	}
}
