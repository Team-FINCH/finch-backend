package com.finch.domain.stock.service;

import com.finch.domain.price.event.PriceObservedEvent;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.repository.StockRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * KIS 현재가에 실려 온 기준가·거래정지를 종목 마스터에 반영한다 ({@link PriceObservedEvent}).
 * <p>
 * stock 이 price 를 부르는 것이 아니라 price 가 던진 이벤트를 stock 이 받는다 — 같은 층끼리는 이벤트로만 이야기한다.
 * 마스터 파일에는 거래정지 사유가 없어 지금까지 {@code suspended_reason} 이 거의 비어 있었는데, 이 경로가 그 값을 채운다.
 * <p>
 * <b>값이 같으면 UPDATE 를 내지 않는다.</b> 발행 측도 바뀐 종목만 보내지만(재시작하면 첫 틱은 전부 보낸다) 여기서 한 번 더 거른다.
 * 동기 리스너다 — 폴링 스레드에서 돌고, 실패해도 캐시는 이미 갱신됐으므로 시세 조회에는 영향이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceObservedListener {

	private final StockRepository stockRepository;

	@EventListener
	@Transactional
	public void on(PriceObservedEvent event) {
		Stock stock = stockRepository.findById(event.stockCode()).orElse(null);
		if (stock == null) {
			return;
		}
		if (stock.applyQuote(event.previousClose(), event.suspended(), event.suspendedReason(), Instant.now())) {
			log.info("종목 갱신(KIS 현재가) code={} previousClose={} suspended={} reason={}", event.stockCode(),
				event.previousClose(), event.suspended(), event.suspendedReason());
		}
	}
}
