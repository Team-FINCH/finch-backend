package com.finch.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.master.CsvSeedLoader;
import com.finch.domain.stock.master.KisMasterFileLoader;
import com.finch.domain.stock.master.StockMasterLoadException;
import com.finch.domain.stock.master.StockMasterRow;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.domain.stock.service.StockMasterSyncService.SyncResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 동기화의 판정 — UPSERT, 전 종목 출처에서만 비활성화, KIS 실패 시 CSV 폴백 — 을 목으로 본다. DB 효과는 {@code StockServiceTest} 의
 * 기동 적재가 본다. 여기서는 로더 조합을 마음대로 바꿀 수 있어야 해서 스프링을 띄우지 않는다.
 */
class StockMasterSyncServiceTest {

	private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

	@SuppressWarnings("unchecked")
	private final ObjectProvider<KisMasterFileLoader> kisProvider = mock(ObjectProvider.class);
	private final KisMasterFileLoader kis = mock(KisMasterFileLoader.class);
	private final CsvSeedLoader csv = mock(CsvSeedLoader.class);
	private final StockRepository repository = mock(StockRepository.class);
	private final StockMasterSyncService service = new StockMasterSyncService(kisProvider, csv, repository);

	@Test
	@DisplayName("전 종목 출처(KIS) — 새 종목은 INSERT, 있는 종목은 갱신, 사라진 종목은 is_active=false")
	void upsertsAndDeactivatesWithCompleteSource() {
		Stock existing = Stock.of("005930", "삼성전자(구)", Market.KOSPI, false, null, 70_000L, T0);
		Stock gone = Stock.of("999999", "사라진종목", Market.KOSDAQ, false, null, 1_000L, T0);
		given(repository.findAll()).willReturn(List.of(existing, gone));
		givenKis(true, List.of(
			new StockMasterRow("005930", "삼성전자", Market.KOSPI, true, "거래정지", 73_500L),
			new StockMasterRow("000660", "SK하이닉스", Market.KOSPI, false, null, 200_000L)));

		SyncResult result = service.sync();

		assertThat(result.source()).isEqualTo("KIS");
		assertThat(result.inserted()).isEqualTo(1);
		assertThat(result.updated()).isEqualTo(1);
		assertThat(result.deactivated()).isEqualTo(1);
		assertThat(result.total()).isEqualTo(2);
		// 갱신 — 이름·정지·기준가가 마스터 값으로 바뀌고 활성이다.
		assertThat(existing.getStockName()).isEqualTo("삼성전자");
		assertThat(existing.isSuspended()).isTrue();
		assertThat(existing.getSuspendedReason()).isEqualTo("거래정지");
		assertThat(existing.getPreviousClose()).isEqualTo(73_500L);
		assertThat(existing.isActive()).isTrue();
		// 사라진 종목 — 지우지 않고 비활성.
		assertThat(gone.isActive()).isFalse();
		// 신설 — saveAll 에 한 건.
		List<Stock> saved = savedStocks();
		assertThat(saved).hasSize(1);
		assertThat(saved.getFirst().getStockCode()).isEqualTo("000660");
		assertThat(saved.getFirst().isActive()).isTrue();
	}

	/** 폴백한 시드로 2,400 종목이 비활성화되면 검색이 통째로 망가진다. 일부 출처는 절대 비활성화하지 않는다. */
	@Test
	@DisplayName("일부 출처(CSV) — 목록에 없는 종목을 비활성화하지 않는다")
	void doesNotDeactivateWithPartialSource() {
		Stock notInSeed = Stock.of("999999", "시드밖종목", Market.KOSDAQ, false, null, 1_000L, T0);
		given(repository.findAll()).willReturn(List.of(notInSeed));
		given(kisProvider.getIfAvailable()).willReturn(null);
		givenCsv(List.of(new StockMasterRow("005930", "삼성전자", Market.KOSPI, false, null, 73_500L)));

		SyncResult result = service.sync();

		assertThat(result.source()).isEqualTo("CSV 시드");
		assertThat(result.deactivated()).isZero();
		assertThat(notInSeed.isActive()).isTrue();
		assertThat(savedStocks()).extracting(Stock::getStockCode).containsExactly("005930");
	}

	/** backend_story S5 — 마스터 파싱 실패가 서비스 기동을 막지 않는다. */
	@Test
	@DisplayName("KIS 가 실패하면 WARN 을 남기고 CSV 시드로 폴백한다 — 비활성화도 하지 않는다")
	void fallsBackToCsvWhenKisFails() {
		Stock existing = Stock.of("999999", "기존종목", Market.KOSDAQ, false, null, 1_000L, T0);
		given(repository.findAll()).willReturn(List.of(existing));
		given(kisProvider.getIfAvailable()).willReturn(kis);
		given(kis.sourceName()).willReturn("KIS");
		given(kis.complete()).willReturn(true);
		given(kis.load()).willThrow(new StockMasterLoadException("포맷 변경"));
		givenCsv(List.of(new StockMasterRow("005930", "삼성전자", Market.KOSPI, false, null, 73_500L)));

		SyncResult result = service.sync();

		assertThat(result.source()).isEqualTo("CSV 시드");
		assertThat(result.inserted()).isEqualTo(1);
		assertThat(result.deactivated()).isZero();
		assertThat(existing.isActive()).isTrue();
	}

	@Test
	@DisplayName("마스터가 기준가를 주지 않으면 기존 previous_close 를 지우지 않는다")
	void keepsPreviousCloseWhenMasterHasNone() {
		Stock existing = Stock.of("005930", "삼성전자", Market.KOSPI, false, null, 70_000L, T0);
		given(repository.findAll()).willReturn(List.of(existing));
		givenKis(true, List.of(new StockMasterRow("005930", "삼성전자", Market.KOSPI, false, null, null)));

		service.sync();

		assertThat(existing.getPreviousClose()).isEqualTo(70_000L);
		verify(csv, never()).load();
	}

	@Test
	@DisplayName("마스터에 다시 나타난 종목은 활성으로 돌아온다")
	void reactivatesReturningStock() {
		Stock delisted = Stock.of("005930", "삼성전자", Market.KOSPI, false, null, 70_000L, T0);
		delisted.deactivate(T0);
		given(repository.findAll()).willReturn(List.of(delisted));
		givenKis(true, List.of(new StockMasterRow("005930", "삼성전자", Market.KOSPI, false, null, 73_500L)));

		service.sync();

		assertThat(delisted.isActive()).isTrue();
	}

	private void givenKis(boolean complete, List<StockMasterRow> rows) {
		given(kisProvider.getIfAvailable()).willReturn(kis);
		given(kis.sourceName()).willReturn("KIS");
		given(kis.complete()).willReturn(complete);
		given(kis.load()).willReturn(rows);
	}

	private void givenCsv(List<StockMasterRow> rows) {
		given(csv.sourceName()).willReturn("CSV 시드");
		given(csv.complete()).willReturn(false);
		given(csv.load()).willReturn(rows);
	}

	@SuppressWarnings("unchecked")
	private List<Stock> savedStocks() {
		ArgumentCaptor<List<Stock>> captor = ArgumentCaptor.forClass(List.class);
		verify(repository).saveAll(captor.capture());
		return new ArrayList<>(captor.getValue());
	}
}
