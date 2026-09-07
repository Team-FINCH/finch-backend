package com.finch.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * `finch.*` 설정의 루트다. 우리가 정한 값은 전부 이 접두사 아래 모으고, 스프링·라이브러리 설정
 * (`spring.*`, `management.*`)과 섞지 않는다 — 남의 키 이름과 충돌하지 않고, 우리 값만 한눈에 본다.
 * <p>
 * 여기에는 <b>어느 도메인에도 속하지 않는 값</b>만 둔다. 도메인 고유 설정은 자기 패키지에
 * `@ConfigurationProperties("finch.{도메인}")` 을 따로 두고 접두사만 공유한다
 * (예: {@code finch.idempotency} 는 {@code global/idempotency} 가, {@code finch.price} 는 price 도메인이).
 * 전부 이 한 클래스에 중첩시키면 {@code global} 이 모든 도메인의 설정 모양을 알게 되는데,
 * 그것은 "global 은 도메인을 참조하지 않는다"(backConvention 2.4)와 반대 방향이다.
 * <p>
 * 값이 통째로 없어도 기동해야 하므로 중첩 레코드에 {@link DefaultValue} 를 붙인다.
 * 붙이지 않으면 `finch.market` 한 줄이 빠졌을 때 바인딩이 null 을 넣고 첫 호출에서 NPE 가 난다.
 */
@ConfigurationProperties("finch")
public record FinchProperties(@DefaultValue Market market, @DefaultValue Http http,
	@DefaultValue LeaderLock leaderLock) {

	/**
	 * 장 시간 판정 ({@code MarketClock}).
	 *
	 * @param alwaysOpen 장 시간을 무시하고 항상 열린 것으로 본다. 시연·테스트 전용이고 운영에서는 false 다.
	 *                   주문(apiSpec 7.2)이 평일 09:00~15:30 밖에서 전부 막히면 발표 시간대에 데모가 불가능하다.
	 */
	public record Market(@DefaultValue("false") boolean alwaysOpen) {
	}

	/**
	 * 외부 HTTP 호출의 공통값 ({@code WebClientConfig}).
	 *
	 * @param connectTimeout TCP 연결까지의 제한. 응답 대기 시간은 여기 두지 않는다 — 카카오·KIS·AI 가
	 *                       서로 다르고(AI 는 LLM 이라 90초까지 간다) 공통값으로 묶으면 하나를 늘릴 때
	 *                       나머지도 같이 늘어난다. 연결 실패는 상대가 누구든 빨리 포기하는 것이 맞다.
	 */
	public record Http(@DefaultValue("3s") Duration connectTimeout) {
	}

	/**
	 * 리더 락 ({@code global/lock/LeaderLock}). replica 중 1대만 KIS 폴링·일봉 배치를 돌린다.
	 *
	 * @param ttl           락의 수명. 리더가 죽으면 이 시간 뒤에 다른 인스턴스가 이어받는다.
	 * @param renewInterval 리더가 락을 늘리는 주기. TTL 보다 충분히 짧아야 살아 있는 리더가 락을 놓치지 않는다.
	 */
	public record LeaderLock(@DefaultValue("10s") Duration ttl, @DefaultValue("3s") Duration renewInterval) {
	}
}
