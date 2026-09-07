package com.finch.domain.price.feed.kis;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * KIS 실공급자 설정 ({@code finch.kis}). {@code finch.price.provider=kis} 일 때만 빈들이 이 값을 읽는다.
 * <p>
 * <b>{@code keys} 는 목록이다.</b> KIS 의 초당 호출 한도와 실시간 등록 한도가 앱키(계정) 단위라, 키를 더하면 수용량이 그만큼 는다
 * (apiSpec 5.6 관계식 4). 지금은 1개로 운영하고 부족하면 yaml 항목과 시크릿만 더한다 — 코드는 "키가 하나" 라고 가정하지 않는다.
 * 첫 항목은 {@code ${KIS_APP_KEY}}/{@code ${KIS_APP_SECRET}} 이고 비밀값 규칙대로 기본값이 없다.
 * <p>
 * 환경변수 인덱스 바인딩({@code FINCH_KIS_KEYS_1_APPKEY})만으로 늘리는 방식은 쓰지 않는다 — 목록은 프로퍼티 소스 간에 병합되지
 * 않아 yaml 의 첫 항목이 사라진다. 항목은 yaml 에 적고 값만 환경변수로 받는다.
 *
 * @param baseUrl            실전 {@code https://openapi.koreainvestment.com:9443}, 모의 {@code https://openapivts.koreainvestment.com:29443}.
 * @param keys               앱키 풀. provider=kis 면 1개 이상이어야 한다.
 * @param pollInterval       관심 종목 순회 주기 (apiSpec 5.6 "3초 목표"). {@code stale-after}(10초)보다 짧아야 한다 (관계식 3).
 * @param ratePerSecond      <b>키당</b> 초당 호출 상한. S0-1 가정값 20 (실전 계정). 모의 계정은 2 다. 실측 후 값만 바꾼다.
 * @param timeout            호출 하나의 응답 대기 제한.
 * @param tokenRefreshMargin 접근토큰을 만료 이 시간 전에 버린다. KIS 토큰은 24시간이고 재발급은 키당 분당 1회로 제한된다.
 * @param autoStart          기동과 함께 폴링을 돌린다. 테스트는 false 로 두고 {@code tick()} 을 직접 부른다 (Fake 와 같다).
 */
@ConfigurationProperties("finch.kis")
public record KisProperties(
	@DefaultValue("https://openapi.koreainvestment.com:9443") String baseUrl,
	List<KisCredential> keys,
	@DefaultValue("3s") Duration pollInterval,
	@DefaultValue("20") int ratePerSecond,
	@DefaultValue("5s") Duration timeout,
	@DefaultValue("5m") Duration tokenRefreshMargin,
	@DefaultValue("true") boolean autoStart
) {

	public KisProperties {
		keys = keys == null ? List.of() : List.copyOf(keys);
	}
}
