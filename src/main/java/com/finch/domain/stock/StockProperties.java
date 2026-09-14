package com.finch.domain.stock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 종목 도메인 설정 ({@code finch.stock}). 도메인 패키지에 두는 이유는 {@code AccountProperties} 와 같다.
 *
 * @param master      종목 마스터 적재.
 * @param seedOnEmpty true 면 기동 시 {@code daily_candle} 이 비어 있을 때 {@code resources/stock/candles-seed.csv} 를 넣는다.
 *                    프론트가 차트를 바로 그리게 하는 시연용이고, S10 이 실데이터를 채우면 false 로 내린다.
 */
@ConfigurationProperties("finch.stock")
public record StockProperties(@DefaultValue Master master, @DefaultValue("true") boolean seedOnEmpty,
	@DefaultValue Candle candle) {

	/**
	 * @param source        {@code kis} 면 KIS 마스터 파일을 내려받고, {@code csv} 면 {@code resources/stock/seed.csv} 만 쓴다.
	 *                      테스트·오프라인은 csv 다. kis 가 실패하면 어느 쪽이든 csv 로 폴백한다.
	 * @param baseUrl       마스터 파일 호스트. 무인증이다 — 앱키 없이 받는 정적 파일이라 KIS 실공급자(S10)와 무관하다.
	 * @param timeout       zip 하나(약 120KB) 응답 대기 제한.
	 * @param syncOnStartup 기동 시 {@code stock} 테이블이 비어 있으면 1회 적재한다. 비어 있지 않으면 스케줄에 맡긴다.
	 * @param cron          정기 동기화. 07:00 KST — 장 시작 전, 전일 상장·폐지가 마스터에 반영된 뒤다.
	 */
	public record Master(
		@DefaultValue("kis") Source source,
		@DefaultValue("https://new.real.download.dws.co.kr/common/master") String baseUrl,
		@DefaultValue("30s") Duration timeout,
		@DefaultValue("true") boolean syncOnStartup,
		@DefaultValue("0 0 7 * * *") String cron
	) {
	}

	/**
	 * 일봉 적재 ({@code CandleSyncService}, S10).
	 *
	 * @param backfillDays    봉이 없는 종목을 처음 볼 때 받아 오는 기간. 3년 = 차트의 가장 긴 탭(3Y)이다.
	 * @param cron            일일 갱신. 20:30 KST — 애프터마켓(16:00~20:00)까지 끝나야 당일 봉이 확정된다. 리더만 돈다.
	 * @param warmUpOnStartup 기동 시 서비스 종목({@code finch.universe})의 봉을 미리 채운다 — 첫 차트 조회의 8회 호출 대기를 없앤다.
	 *                        봉이 있는 종목은 건너뛰므로 첫 배포 때 한 번이다. 리더만, 배경 스레드에서 ({@code StockStartupRunner}).
	 */
	public record Candle(@DefaultValue("1095") int backfillDays, @DefaultValue("0 30 20 * * *") String cron,
		@DefaultValue("true") boolean warmUpOnStartup) {
	}

	public enum Source {
		KIS,
		CSV
	}
}
