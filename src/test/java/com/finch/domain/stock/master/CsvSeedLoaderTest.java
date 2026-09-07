package com.finch.domain.stock.master;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.domain.stock.entity.Market;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 시드 파일이 손으로 편집되다 깨지는 것을 막는다. Docker 없이 돈다. */
class CsvSeedLoaderTest {

	private final CsvSeedLoader loader = new CsvSeedLoader();

	@Test
	@DisplayName("시드는 코스피·코스닥 300 종목 남짓이고 코드는 6자리·유일하다")
	void loadsSeed() {
		List<StockMasterRow> rows = loader.load();

		assertThat(rows).hasSizeGreaterThanOrEqualTo(300);
		assertThat(rows).extracting(StockMasterRow::stockCode).allMatch(c -> c.matches("[0-9A-Z]{6}")).doesNotHaveDuplicates();
		assertThat(rows).extracting(StockMasterRow::market).contains(Market.KOSPI, Market.KOSDAQ);
		assertThat(rows).extracting(StockMasterRow::stockName).allMatch(n -> !n.isBlank());
	}

	/** 일봉 시드 5종목은 반드시 있어야 한다 — 없으면 캔들 시드가 FK 때문에 그 종목을 건너뛴다. */
	@Test
	@DisplayName("일봉 시드 5종목(삼성전자·SK하이닉스·NAVER·카카오·현대차)이 들어 있다")
	void containsCandleSeedStocks() {
		List<String> codes = loader.load().stream().map(StockMasterRow::stockCode).toList();

		assertThat(codes).contains("005930", "000660", "035420", "035720", "005380");
		assertThat(loader.load().stream().filter(r -> r.stockCode().equals("005930")).findFirst().orElseThrow())
			.satisfies(s -> {
				assertThat(s.stockName()).isEqualTo("삼성전자");
				assertThat(s.market()).isEqualTo(Market.KOSPI);
				assertThat(s.referencePrice()).isPositive();
			});
	}

	@Test
	@DisplayName("일부 종목뿐이라 complete 가 아니다 — 시드로 동기화해도 나머지를 비활성화하지 않는다")
	void isNotComplete() {
		assertThat(loader.complete()).isFalse();
		assertThat(loader.sourceName()).isNotBlank();
	}
}
