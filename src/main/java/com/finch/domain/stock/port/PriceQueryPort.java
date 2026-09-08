package com.finch.domain.stock.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 현재가를 묻는 창구. <b>{@code stock}(1층)이 선언하고 {@code price}(1층, S7)가 구현한다.</b>
 * <p>
 * 검색 결과와 종목 상세에는 현재가·등락이 붙는데 그 값은 Redis 시세 캐시(price 소유)에 있다. 같은 1층이라 stock 이
 * price 를 직접 부를 수 없다 (backConvention 2.4 규칙 2 — 같은 층 참조 금지). 그래서 필요한 쪽이 인터페이스를 선언하고
 * 가진 쪽이 구현한다 (의존성 역전, {@code ValuationPort} 와 같은 방식). 컴파일 의존은 price → stock 한 방향이다.
 * <p>
 * 값이 없을 때의 모양은 apiSpec 5.4 의 세 상태 표가 정한다 — 이 포트의 계약이지 구현체의 재량이 아니다.
 */
public interface PriceQueryPort {

	/** 한 종목. 캐시 미스면 {@link PriceSnapshot#missing()}. */
	PriceSnapshot latest(String stockCode);

	/**
	 * 여러 종목을 한 번에. 검색 결과(최대 10)·관심 목록(최대 50)이 N+1 을 만들지 않게 한다.
	 * 돌려주는 맵은 <b>요청한 모든 코드를 키로 갖는다</b> — 없는 종목도 {@link PriceSnapshot#missing()} 으로 채운다.
	 */
	Map<String, PriceSnapshot> latestAll(Collection<String> stockCodes);

	/**
	 * 진행 중인 당일 봉. 캔들 응답의 마지막에 얹는 값이다 (apiSpec 5.3).
	 * <p>
	 * <b>저장하지 않는 값이라 포트로 받는다.</b> 확정되기 전의 봉을 {@code daily_candle} 에 넣으면 다시 고칠 경로가 없어
	 * 영영 굳는다 ({@code CandleSyncService.backfillIfEmpty} 주석). 그래서 응답을 만들 때마다 시세 캐시에서 새로 읽는다.
     * <p>
	 * 봉을 만들 재료가 없으면 {@link java.util.Optional#empty()} 다 — 캐시 미스, 옛 캐시 값, 시가·고가·저가를 모르는
	 * 공급자가 모두 여기 해당한다. 그때 차트는 확정된 봉만 그린다.
	 */
	java.util.Optional<SessionBar> sessionBar(String stockCode);

	/**
	 * 아직 끝나지 않은 하루의 봉. 필드 뜻은 {@code CandleRes.Candle} 과 같다.
	 *
	 * @param date  이 봉의 거래일(KST). 확정된 마지막 봉보다 뒤일 때만 쓴다 — 같거나 앞이면 배치가 이미 넣은 것이다.
	 * @param close 지금 현재가다. 장이 끝나면 그대로 종가가 된다.
	 */
	record SessionBar(java.time.LocalDate date, long open, long high, long low, long close, long volume) {
	}

	/**
	 * apiSpec 5.4 의 세 상태.
	 * <ul>
	 *   <li>정상 수신 — 값 있음, {@code stale=false}</li>
	 *   <li>수신 끊김 — 마지막 값 유지, {@code stale=true}</li>
	 *   <li>값 없음 — 전부 null, {@code stale=true}</li>
	 * </ul>
	 *
	 * @param changeRate 소수 둘째 자리 HALF_UP (backConvention 6장). 계산은 시세 쪽이 한다.
	 */
	record PriceSnapshot(Long currentPrice, Long changeAmount, BigDecimal changeRate, Instant asOf, boolean stale) {

		public static PriceSnapshot missing() {
			return new PriceSnapshot(null, null, null, null, true);
		}
	}
}
