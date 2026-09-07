package com.finch.domain.watchlist.service;

import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.port.HoldingQueryPort;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.domain.stock.service.StockService;
import com.finch.domain.watchlist.dto.request.WatchlistSort;
import com.finch.domain.watchlist.dto.response.WatchlistRes;
import com.finch.domain.watchlist.entity.WatchlistItem;
import com.finch.domain.watchlist.exception.WatchlistErrorCode;
import com.finch.domain.watchlist.repository.WatchlistItemRepository;
import com.finch.domain.watchlist.repository.WatchlistRow;
import com.finch.global.exception.CustomException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관심 종목 (apiSpec 6.3, featureSpec 6장). 최대 50개, 등록·해제·목록.
 * <p>
 * watchlist(4층)가 stock(1층)을 참조한다 — 종목 존재 판정은 {@link StockService}, 종목명은 조인, 시세와 보유 여부는 stock 이
 * 선언한 포트다 (backConvention 2.4). 아래층이 위층을 부르는 자리가 없다.
 */
@Service
@RequiredArgsConstructor
public class WatchlistService {

	/** apiSpec 6.3 · featureSpec 6장이 정한 상한. 응답의 {@code maxCount} 로 그대로 나간다. */
	static final int MAX_ITEMS = 50;

	private final WatchlistItemRepository watchlistItemRepository;
	private final StockService stockService;
	private final PriceQueryPort priceQueryPort;
	private final HoldingQueryPort holdingQueryPort;

	/**
	 * 등록 (apiSpec 6.3). 판정 순서는 11.2 표 그대로 — 종목 존재 → 중복 → 한도.
	 * <p>
	 * <b>중복을 앱과 DB 두 곳에서 막는다.</b> 앱 검사는 <b>순서</b>를 위한 것이다: 50개가 찬 상태에서 이미 담긴 종목을 다시 담으면
	 * {@code ALREADY_EXISTS} 여야 하는데, DB 제약에만 맡기면 INSERT 가 한도 검사 뒤라 {@code LIMIT_EXCEEDED} 가 먼저 나간다.
	 * DB 제약은 <b>경합</b>을 위한 것이다: 토글을 두 번 빠르게 누르면 두 요청이 같은 순간에 "없음"을 보고 둘 다 INSERT 한다.
	 * 둘 중 하나만 남기는 것은 DB 만 할 수 있다.
	 * <p>
	 * 한도도 마찬가지로 경합에 완전하지는 않다 — 51번째가 동시에 둘 들어오면 51개가 될 수 있다. DB 제약으로 표현할 수 없는 규칙이고
	 * (erd.md §2.9), 관심 종목이 하나 더 담기는 것은 돈이 움직이는 일이 아니라 계좌 락 같은 장치를 두지 않는다.
	 * <p>
	 * 거래정지 종목도 담을 수 있다. 관심은 매매가 아니고, 화면이 뱃지로 알린다 (featureSpec 4장).
	 */
	@Transactional
	public void add(Long userId, String stockCode) {
		// getTradable 은 주문(S9)을 위해 만든 판정이지만 여기 필요한 것과 같다 — 활성 종목인가. 상장폐지도 exists=false 다.
		if (!stockService.getTradable(stockCode).exists()) {
			throw new CustomException(StockErrorCode.STOCK_NOT_FOUND);
		}
		if (watchlistItemRepository.existsByUserIdAndStockCode(userId, stockCode)) {
			throw new CustomException(WatchlistErrorCode.WATCHLIST_ALREADY_EXISTS);
		}
		if (watchlistItemRepository.countByUserId(userId) >= MAX_ITEMS) {
			throw new CustomException(WatchlistErrorCode.WATCHLIST_LIMIT_EXCEEDED);
		}
		try {
			// saveAndFlush 다 — save 만 하면 INSERT 가 트랜잭션 끝에 나가서 제약 위반을 여기서 잡을 수 없다.
			watchlistItemRepository.saveAndFlush(WatchlistItem.of(userId, stockCode, Instant.now()));
		} catch (DataIntegrityViolationException e) {
			throw new CustomException(WatchlistErrorCode.WATCHLIST_ALREADY_EXISTS);
		}
	}

	/** 해제. 담긴 적 없어도 조용히 끝난다 — 응답은 204 다 (apiSpec 11.2). 토글이라 이미 꺼진 것을 다시 끄는 것이 정상 경로다. */
	@Transactional
	public void remove(Long userId, String stockCode) {
		watchlistItemRepository.deleteByUserIdAndStockCode(userId, stockCode);
	}

	/**
	 * 목록 (apiSpec 6.3). 정렬 위치가 값마다 다르다 (erd.md §2.9).
	 * <p>
	 * {@code REGISTERED}·{@code NAME} 은 DB 가 정렬한다. {@code CHANGE_RATE} 는 등락률이 Redis 시세 캐시에 있어 DB 가 볼 수 없으므로
	 * 등록순으로 읽어 온 뒤 애플리케이션이 다시 세운다. 시세가 없는 종목은 뒤로 민다 — 값이 없는 것을 0%로 취급하면 실제로 0% 인
	 * 종목과 섞인다.
	 * <p>
	 * 시세와 보유 여부 모두 벌크 1회다. 50개짜리 목록에서 낱개로 물으면 그대로 N+1 이 된다.
	 */
	@Transactional(readOnly = true)
	public WatchlistRes list(Long userId, WatchlistSort sort) {
		List<WatchlistRow> rows = sort == WatchlistSort.NAME
			? watchlistItemRepository.findRowsByName(userId)
			: watchlistItemRepository.findRowsByRegistered(userId);

		List<String> codes = rows.stream().map(WatchlistRow::getStockCode).toList();
		Map<String, PriceSnapshot> prices = priceQueryPort.latestAll(codes);
		Set<String> held = holdingQueryPort.heldCodesAmong(userId, codes);

		List<WatchlistRes.Item> items = new ArrayList<>(rows.stream()
			.map(row -> WatchlistRes.Item.of(row, prices.getOrDefault(row.getStockCode(), PriceSnapshot.missing()),
				held.contains(row.getStockCode())))
			.toList());
		if (sort == WatchlistSort.CHANGE_RATE) {
			items.sort(Comparator.comparing(WatchlistRes.Item::changeRate,
				Comparator.nullsLast(Comparator.reverseOrder())));
		}
		return new WatchlistRes(items.size(), MAX_ITEMS, items);
	}
}
