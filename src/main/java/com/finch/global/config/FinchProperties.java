package com.finch.global.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
	@DefaultValue LeaderLock leaderLock, @DefaultValue Internal internal, @DefaultValue Universe universe) {

	/** 생성자가 둘이라 Boot 에게 바인딩에 쓸 쪽(정식 생성자)을 알려 준다 — 없으면 기본 생성자를 찾다 기동에 실패한다. */
	@ConstructorBinding
	public FinchProperties {
	}

	/** 종목 범위 없이 만드는 생성자. 범위가 생기기 전의 테스트들이 쓴다. */
	public FinchProperties(Market market, Http http, LeaderLock leaderLock, Internal internal) {
		this(market, http, leaderLock, internal, Universe.unrestricted());
	}

	/**
	 * 서비스 종목 범위 ({@code StockUniverse}). 켜면 이 목록 밖의 종목은 서비스에 없는 종목이다 — 검색에서 빠지고, 상세·시세·주문·관심
	 * 등록은 {@code STOCK_NOT_FOUND} 이고, AI 종목 분석 중계도 AI 서버까지 가지 않는다.
	 * <p>
	 * 왜 두는가 — 시세는 KIS 웹소켓이 고정 등록하는 종목(finch.kis.realtime.codes, 같은 목록)만 실시간이고, 그 밖은 REST 폴링이라
	 * 2건/초 한도에서 6종목이 상한이다. AI 종목 분석도 종목마다 LLM 을 부르므로 범위를 좁혀야 GMS 크레딧이 버틴다. 시연 범위를
	 * 30종목으로 정한 이유다 (이슈 #253).
	 * <p>
	 * 종목 마스터는 전 종목 그대로 적재한다 — 이름·기준가는 필요하고 비용이 없다. 범위는 마스터가 아니라 <b>판정</b>에서 건다.
	 *
	 * @param enabled false 면 범위가 없다 — 마스터의 모든 활성 종목이 서비스 대상이다. 테스트 설정이 false 로 둔다.
	 * @param codes   범위. enabled 면 1개 이상이어야 한다.
	 */
	public record Universe(@DefaultValue("false") boolean enabled, List<String> codes) {

		public Universe {
			codes = codes == null ? List.of() : List.copyOf(new LinkedHashSet<>(codes));
			if (enabled && codes.isEmpty()) {
				throw new IllegalStateException("finch.universe.enabled=true 인데 codes 가 비어 있다");
			}
		}

		public static Universe unrestricted() {
			return new Universe(false, List.of());
		}
	}

	/**
	 * 장 시간 판정 ({@code MarketClock}).
	 *
	 * @param alwaysOpen 장 시간을 무시하고 항상 열린 것으로 본다. 시연·테스트 전용이고 운영에서는 false 다.
	 *                   주문(apiSpec 7.2)이 장 시간(평일 09:00~15:30·16:00~20:00) 밖에서 전부 막히면 발표 시간대에 데모가 불가능하다.
	 */
	public record Market(@DefaultValue("false") boolean alwaysOpen) {
	}

	/**
	 * 외부 HTTP 호출의 공통값 ({@code WebClientConfig}).
	 *
	 * @param connectTimeout TCP 연결까지의 제한. 응답 대기 시간은 여기 두지 않는다 — 카카오·KIS·AI 가
	 *                       서로 다르고(AI 는 LLM 이라 90초까지 간다) 공통값으로 묶으면 하나를 늘릴 때
	 *                       나머지도 같이 늘어난다. 연결 실패는 상대가 누구든 빨리 포기하는 것이 맞다.
	 * @param maxIdleTime    커넥션 풀이 유휴 연결을 들고 있는 시간. <b>상대 서버의 keep-alive 보다 짧아야
	 *                       한다.</b> 길면 서버가 이미 닫은 연결을 풀에서 꺼내 쓰고
	 *                       {@code Connection prematurely closed BEFORE response} 로 실패한다. AI 서버의
	 *                       uvicorn 이 기본 5초이므로 그보다 짧은 2초로 둔다. 사람이 눌러야 도는 AI 호출은
	 *                       요청 간격이 5초를 넘기 쉬워 이 값이 없으면 간헐적으로 실패한다.
	 * @param evictInterval  풀이 유휴 연결을 걷어내는 주기. 꺼낼 때도 {@code maxIdleTime} 을 보지만,
	 *                       주기적으로 미리 닫아 두면 반쯤 닫힌 연결을 잡을 확률이 줄어든다.
	 */
	public record Http(@DefaultValue("3s") Duration connectTimeout, @DefaultValue("2s") Duration maxIdleTime,
		@DefaultValue("30s") Duration evictInterval) {
	}

	/**
	 * 리더 락 ({@code global/lock/LeaderLock}). replica 중 1대만 KIS 폴링·일봉 배치를 돌린다.
	 *
	 * @param ttl           락의 수명. 리더가 죽으면 이 시간 뒤에 다른 인스턴스가 이어받는다.
	 * @param renewInterval 리더가 락을 늘리는 주기. TTL 보다 충분히 짧아야 살아 있는 리더가 락을 놓치지 않는다.
	 */
	public record LeaderLock(@DefaultValue("10s") Duration ttl, @DefaultValue("3s") Duration renewInterval) {
	}

	/**
	 * {@code /internal/v1} 인증 ({@code global/security/InternalTokenFilter}).
	 *
	 * @param token AI 서버가 {@code X-Internal-Token} 으로 보내는 값과 같아야 한다. 비밀값이라 기본값이 없고, 비어 있으면 그 경로는
	 *              전부 401 이다. 백엔드 → AI 방향의 토큰({@code finch.ai.internal-token})과는 <b>별도 변수</b>다 — 한쪽이 새면
	 *              한쪽만 바꾼다.
	 */
	public record Internal(String token) {
	}
}
