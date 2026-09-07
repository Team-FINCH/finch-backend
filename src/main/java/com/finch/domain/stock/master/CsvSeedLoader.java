package com.finch.domain.stock.master;

import com.finch.domain.stock.entity.Market;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * {@code resources/stock/seed.csv} — 코스피·코스닥 상위 300 종목 (2026-09-07 KIS 마스터에서 기준가×상장주수 순으로 뽑고,
 * 일봉 시드 5종목을 강제 포함). 테스트·오프라인의 기본 출처이고 KIS 다운로드가 실패했을 때의 폴백이다.
 * <p>
 * {@link #complete()} 가 false 다 — 일부 종목뿐이라 "여기 없는 종목 = 상장폐지"가 아니다. KIS 로 2,700 종목을 적재한 뒤
 * 어느 날 다운로드가 실패해 이 시드로 폴백했을 때 나머지 2,400 종목이 비활성화되면 검색이 통째로 망가진다.
 * <p>
 * CSV 는 헤더 한 줄 + {@code stock_code,stock_name,market,suspended,reference_price}. 종목명에 쉼표·따옴표가 없음을 생성 시 확인했다.
 */
@Component
public class CsvSeedLoader implements StockMasterLoader {

	static final String PATH = "stock/seed.csv";

	@Override
	public List<StockMasterRow> load() {
		List<StockMasterRow> rows = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(new ClassPathResource(PATH).getInputStream(), StandardCharsets.UTF_8))) {
			String header = reader.readLine();
			if (header == null || !header.startsWith("stock_code,")) {
				throw new StockMasterLoadException(PATH + " 헤더가 다르다: " + header);
			}
			String line;
			int lineNo = 1;
			while ((line = reader.readLine()) != null) {
				lineNo++;
				if (line.isBlank()) {
					continue;
				}
				String[] cols = line.split(",", -1);
				if (cols.length != 5) {
					throw new StockMasterLoadException(PATH + " " + lineNo + "번째 줄 컬럼 수가 " + cols.length + "이다");
				}
				boolean suspended = Boolean.parseBoolean(cols[3]);
				Long reference = cols[4].isBlank() ? null : Long.parseLong(cols[4]);
				rows.add(new StockMasterRow(cols[0], cols[1], Market.valueOf(cols[2]), suspended,
					suspended ? "거래정지" : null, reference != null && reference > 0 ? reference : null));
			}
		} catch (IOException e) {
			throw new StockMasterLoadException(PATH + " 를 읽지 못했다", e);
		}
		if (rows.isEmpty()) {
			throw new StockMasterLoadException(PATH + " 가 비어 있다");
		}
		return rows;
	}

	@Override
	public String sourceName() {
		return "CSV 시드";
	}

	@Override
	public boolean complete() {
		return false;
	}
}
