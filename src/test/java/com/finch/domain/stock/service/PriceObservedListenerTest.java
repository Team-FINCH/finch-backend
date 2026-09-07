package com.finch.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.event.PriceObservedEvent;
import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.repository.StockRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** price 가 던진 이벤트로 stock 이 기준가·거래정지를 따라잡는다. 같은 값이면 행을 건드리지 않는다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PriceObservedListenerTest {

	@Autowired
	private ApplicationEventPublisher publisher;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	@DisplayName("기준가·거래정지·사유가 갱신되고, 같은 값이 다시 오면 updated_at 이 그대로다")
	void appliesQuoteOnlyWhenChanged() throws Exception {
		String code = "ZP0001";
		transactionTemplate.executeWithoutResult(s -> stockRepository.save(
			Stock.of(code, "이벤트테스트", Market.KOSPI, false, null, 1_000L, Instant.parse("2026-09-01T00:00:00Z"))));

		publisher.publishEvent(new PriceObservedEvent(code, 74_400L, true, "관리종목"));

		Stock updated = stockRepository.findById(code).orElseThrow();
		assertThat(updated.getPreviousClose()).isEqualTo(74_400L);
		assertThat(updated.isSuspended()).isTrue();
		assertThat(updated.getSuspendedReason()).isEqualTo("관리종목");
		Instant firstUpdate = updated.getUpdatedAt();
		assertThat(firstUpdate).isAfter(Instant.parse("2026-09-01T00:00:00Z"));

		Thread.sleep(5);
		publisher.publishEvent(new PriceObservedEvent(code, 74_400L, true, "관리종목"));
		assertThat(stockRepository.findById(code).orElseThrow().getUpdatedAt()).isEqualTo(firstUpdate);

		// 기준가를 모르면(null) 기존 값을 지우지 않는다.
		publisher.publishEvent(new PriceObservedEvent(code, null, false, null));
		Stock resumed = stockRepository.findById(code).orElseThrow();
		assertThat(resumed.getPreviousClose()).isEqualTo(74_400L);
		assertThat(resumed.isSuspended()).isFalse();
		assertThat(resumed.getSuspendedReason()).isNull();
	}
}
