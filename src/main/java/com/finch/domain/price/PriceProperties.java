package com.finch.domain.price;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 시세 설정 ({@code finch.price}). 도메인 패키지에 두는 이유는 {@code AccountProperties} 와 같다.
 *
 * @param provider    시세 공급자. {@code fake} 는 랜덤워크(S7), {@code kis} 는 실공급자(S10). 소비 측 코드는 이 값을 모른다 —
 *                    캐시만 읽기 때문이다.
 * @param staleAfter  마지막 수신에서 이 시간이 지나면 {@code stale: true} 다 (apiSpec 5.4). <b>S0-3 가정값 10초</b>이고
 *                    관계식 3 을 지켜야 한다 — KIS 순회 주기(3초)보다 커야 폴링 티어가 상시 "시세 지연" 으로 표시되지 않는다.
 * @param interestTtl 관심 신호의 수명 (apiSpec 5.6 폴링 티어 TTL 30초). 이 시간 안에 다시 묻지 않으면 공급자가 그 종목을
 *                    더 채우지 않는다. 관계식 1 — 프론트 폴링 주기(권장 3~5초)의 4~6배라야 슬롯이 플래핑하지 않는다.
 * @param fake        Fake 공급자 설정. {@code provider=kis} 면 쓰이지 않는다.
 */
@ConfigurationProperties("finch.price")
public record PriceProperties(
	@DefaultValue("fake") String provider,
	@DefaultValue("10s") Duration staleAfter,
	@DefaultValue("30s") Duration interestTtl,
	@DefaultValue Fake fake
) {

	/**
	 * @param autoStart    기동과 함께 틱을 돌린다. <b>테스트는 false</b> 다 — 배경 스레드가 캐시를 계속 흔들면 stale 판정처럼
	 *                     시각에 기대는 테스트가 불안정해진다. 테스트는 {@code tick()} 을 직접 부른다.
	 * @param tickInterval 틱 간격.
	 * @param basePrice    처음 보는 종목의 시작 가격. <b>전일 종가를 읽지 않는다</b> — 그러려면 price(1층)가 stock(1층)을
	 *                     참조해야 하고 그것은 같은 층 참조다 (backConvention 2.4 규칙 2). 첫 값을 자기 기준가로 삼는다.
	 * @param maxMoveRate  한 틱의 최대 변동 폭. 0.005 면 ±0.5% 다.
	 * @param minPrice     하한. 랜덤워크가 0 으로 수렴하면 등락률 계산이 무너진다.
	 */
	public record Fake(
		@DefaultValue("true") boolean autoStart,
		@DefaultValue("1s") Duration tickInterval,
		@DefaultValue("50000") long basePrice,
		@DefaultValue("0.005") double maxMoveRate,
		@DefaultValue("100") long minPrice
	) {
	}
}
