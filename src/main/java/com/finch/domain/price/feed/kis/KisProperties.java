package com.finch.domain.price.feed.kis;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param ratePerSecond      <b>키당</b> 초당 호출 상한. 2026-09-14 실측: 모의투자 키는 2 (실전 도메인에서 조회해도 모의 기준이 적용된다).
 *                           실전 키는 20. 값이 KIS 한도보다 크면 리미터가 아무것도 막지 않고 KIS 가 EGW00201 로 거부한다.
 * @param timeout            호출 하나의 응답 대기 제한.
 * @param tokenRefreshMargin 접근토큰을 만료 이 시간 전에 버린다. KIS 토큰은 24시간이고 재발급은 키당 분당 1회로 제한된다.
 * @param autoStart          기동과 함께 폴링·실시간을 돌린다. 테스트는 false 로 두고 {@code tick()} 을 직접 부른다 (Fake 와 같다).
 * @param realtime           웹소켓 실시간 티어 (S12). 꺼져 있으면 폴링이 전부 맡는다.
 */
@ConfigurationProperties("finch.kis")
public record KisProperties(
	@DefaultValue("https://openapi.koreainvestment.com:9443") String baseUrl,
	List<KisCredential> keys,
	@DefaultValue("3s") Duration pollInterval,
	@DefaultValue("20") int ratePerSecond,
	@DefaultValue("5s") Duration timeout,
	@DefaultValue("5m") Duration tokenRefreshMargin,
	@DefaultValue("true") boolean autoStart,
	@DefaultValue Realtime realtime
) {

	/** 생성자가 둘이라 Boot 에게 바인딩에 쓸 쪽(정식 생성자)을 알려 준다 — 없으면 기본 생성자를 찾다 기동에 실패한다. */
	@ConstructorBinding
	public KisProperties {
		keys = keys == null ? List.of() : List.copyOf(keys);
		realtime = realtime == null ? Realtime.disabled() : realtime;
	}

	/** 실시간 설정 없이 만드는 생성자. 실시간 티어가 생기기 전의 테스트들이 쓴다. */
	public KisProperties(String baseUrl, List<KisCredential> keys, Duration pollInterval, int ratePerSecond,
		Duration timeout, Duration tokenRefreshMargin, boolean autoStart) {
		this(baseUrl, keys, pollInterval, ratePerSecond, timeout, tokenRefreshMargin, autoStart, Realtime.disabled());
	}

	/**
	 * 웹소켓 실시간 티어 ({@code finch.kis.realtime}). 세션 하나에 체결가 {@value #MAX_REGISTRATIONS}건까지 등록된다 —
	 * KIS 문서가 아니라 운영 사례에서 확인된 값이고, 넘기면 KIS 가 등록을 거부한다. 우리는 체결가({@code H0STCNT0})만 받으므로
	 * 등록 건수 = 종목 수다. 호가까지 받으면 종목당 2건이라 절반이 된다.
	 *
	 * @param enabled        켜면 {@code KisRealtimeFeed} 가 리더에서 세션을 열고 {@code codes} 를 등록한다.
	 * @param url            실전 {@code ws://ops.koreainvestment.com:21000}, 모의 {@code ws://ops.koreainvestment.com:31000}.
	 *                       REST 의 {@code base-url} 과 짝이 맞아야 한다 — 모의 키로 실전 웹소켓에 붙으면 접속키부터 거절된다.
	 * @param codes          기동과 함께 고정 등록할 종목. 사용자가 보든 말든 세션이 사는 동안 계속 받는다.
	 * @param reconnectDelay 끊기거나 접속에 실패한 뒤 다시 시도하기까지. 너무 짧으면 KIS 가 막힌 상태에서 연속 접속으로 더 맞는다.
	 */
	public record Realtime(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("ws://ops.koreainvestment.com:21000") String url,
		List<String> codes,
		@DefaultValue("5s") Duration reconnectDelay
	) {

		public static final int MAX_REGISTRATIONS = 41;

		public Realtime {
			// 순서를 유지한 채 중복만 걷어낸다 — 같은 종목을 두 번 등록하면 KIS 가 두 번째를 거부하고 한 건을 낭비한다.
			Set<String> unique = codes == null ? Set.of() : new LinkedHashSet<>(codes);
			codes = List.copyOf(unique);
			if (enabled && codes.isEmpty()) {
				throw new IllegalStateException("finch.kis.realtime.enabled=true 인데 codes 가 비어 있다");
			}
			if (codes.size() > MAX_REGISTRATIONS) {
				throw new IllegalStateException("finch.kis.realtime.codes 가 " + codes.size() + "개다. 세션 하나의 등록 한도는 "
					+ MAX_REGISTRATIONS + "건이다 — 키를 더해 세션을 늘리거나 종목을 줄인다");
			}
		}

		static Realtime disabled() {
			return new Realtime(false, "ws://ops.koreainvestment.com:21000", List.of(), Duration.ofSeconds(5));
		}
	}
}
