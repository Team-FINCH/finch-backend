package com.finch.domain.recent.service;

import com.finch.domain.recent.dto.response.RecentViewedRes;
import com.finch.domain.recent.repository.RecentViewedRow;
import com.finch.domain.recent.repository.RecentViewedStockRepository;
import com.finch.domain.stock.event.StockViewedEvent;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.util.StockUniverse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최근 본 종목 (apiSpec 6.1, featureSpec 5장). 최대 30건, 중복 없이 최상단 갱신.
 * <p>
 * <b>등록 API 가 없다.</b> 종목 상세({@code GET /stocks/{stockCode}})가 발행하는 {@link StockViewedEvent} 를 받아 기록한다.
 * stock(1층)이 recent(4층)를 직접 부르면 역방향 참조라 이벤트로 끊었고(backConvention 2.4 규칙 2), 그 반대 방향인
 * recent → stock 은 허용이라 여기서 {@link PriceQueryPort} 를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class RecentViewedService {

	/** apiSpec 6.1 · featureSpec 5장이 정한 상한. 설정값으로 빼지 않는다 — 화면 사양이지 운영 정책이 아니다. */
	static final int MAX_ITEMS = 30;

	private final RecentViewedStockRepository recentViewedStockRepository;
	private final PriceQueryPort priceQueryPort;
	private final StockUniverse universe;

	/**
	 * 종목 상세를 본 사건을 기록한다.
	 * <p>
	 * <b>{@code REQUIRES_NEW} 였다가 걷어냈다</b> (이슈 309). 발행자가 {@code readOnly = true} 트랜잭션 안에서 발행하던 시절에는
	 * 그것이 필요했다 — 스프링은 읽기 전용 트랜잭션의 JDBC 커넥션을 실제로 read-only 로 만들어서, 그 안에서 INSERT 를 하면
	 * Postgres 가 "cannot execute INSERT in a read-only transaction" 으로 거절하기 때문이다.
	 * <p>
	 * 그런데 {@code REQUIRES_NEW} 는 바깥 트랜잭션을 <b>중단할 뿐 커넥션은 반납하지 않는다</b>. 동기 리스너라 같은 스레드에서
	 * 도는 동안 <b>요청 하나가 커넥션 2개를 동시에 점유</b>했고, 풀 크기만큼의 요청이 겹치면 서로의 두 번째 커넥션을 기다리며
	 * 데드락이 됐다. 실측에서 풀 4 · 동시 4건에 재현됐고 트래픽이 끊길 때까지 회복하지 못했다.
	 * <p>
	 * 지금은 {@link com.finch.domain.stock.controller.StockController} 가 트랜잭션 밖에서 발행하므로 이 리스너가 돌 때
	 * 바깥 커넥션이 이미 반납돼 있다. 평범한 {@code @Transactional} 로 충분하고 커넥션도 1개만 쓴다.
	 * <p>
	 * {@code @TransactionalEventListener(AFTER_COMMIT)} 는 해법이 아니다. 커넥션은 {@code cleanupAfterCompletion} 에서 반납되는데
	 * {@code AFTER_COMMIT} 콜백은 그보다 먼저 돌아 커넥션이 아직 잡혀 있다. 게다가 커밋 뒤에만 도는 리스너는 테스트가
	 * 트랜잭션으로 감싸여 있으면 <b>아예 실행되지 않아</b>, 통합 테스트에서 "기록이 안 된다"가 조용히 통과한다.
	 * <p>
	 * UPSERT 뒤 곧바로 30건으로 자른다. 두 문장이 한 트랜잭션이라 중간 상태(31건)가 다른 요청에 보이지 않는다.
	 */
	@EventListener
	@Transactional
	public void on(StockViewedEvent event) {
		recentViewedStockRepository.upsert(event.userId(), event.stockCode(), event.viewedAt());
		recentViewedStockRepository.trimBeyond(event.userId(), MAX_ITEMS);
	}

	/** 목록 (apiSpec 6.1). 시세는 벌크 1회로 붙인다 — 30건이면 N+1 이 30번이다. */
	@Transactional(readOnly = true)
	public RecentViewedRes list(Long userId) {
		// 종목 범위가 좁혀지기 전에 본 종목은 숨긴다 — 상세로 가면 404 라 목록에 있으면 갈 곳이 없다. 행은 지우지 않는다.
		List<RecentViewedRow> rows = recentViewedStockRepository.findRows(userId).stream()
			.filter(row -> universe.contains(row.getStockCode())).toList();
		Map<String, PriceSnapshot> prices = priceQueryPort.latestAll(rows.stream().map(RecentViewedRow::getStockCode).toList());
		return new RecentViewedRes(rows.stream()
			.map(row -> RecentViewedRes.Item.of(row, prices.getOrDefault(row.getStockCode(), PriceSnapshot.missing())))
			.toList());
	}

	/** 개별 삭제. 없는 대상이어도 조용히 끝난다 — 응답은 204 다 (apiSpec 11.2). */
	@Transactional
	public void delete(Long userId, String stockCode) {
		recentViewedStockRepository.deleteByUserIdAndStockCode(userId, stockCode);
	}

	@Transactional
	public void deleteAll(Long userId) {
		recentViewedStockRepository.deleteByUserId(userId);
	}
}
