package com.finch.domain.stock.service;

import com.finch.domain.stock.StockProperties;
import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.repository.DailyCandleRepository;
import com.finch.domain.stock.repository.StockRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code daily_candle} 이 비어 있으면 {@code resources/stock/candles-seed.csv} 를 넣는다 — 삼성전자·SK하이닉스·NAVER·카카오·현대차
 * 5종목, 최근 250 영업일. 프론트가 차트를 바로 그리게 하는 <b>시연용 시드</b>이고 실데이터는 S10 일봉 배치가 채운다.
 * <p>
 * 시드 값은 실제 시세가 아니다. 2026-09-07 KIS 마스터의 기준가를 마지막 날 종가로 두고 과거로 랜덤워크한 것이다. 파일 첫 줄
 * 주석이 그 사실을 적고 있다. {@code finch.stock.seed-on-empty=false} 면 아무것도 하지 않는다.
 * <p>
 * 종목이 마스터에 없으면 그 종목의 봉은 건너뛴다 — FK 위반으로 시드 전체가 롤백되는 것보다 낫다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandleSeeder {

	static final String PATH = "stock/candles-seed.csv";

	private final DailyCandleRepository dailyCandleRepository;
	private final StockRepository stockRepository;
	private final StockProperties properties;

	/** @return 넣은 봉 수. 조건이 안 맞으면 0. */
	@Transactional
	public int seedIfEmpty() {
		if (!properties.seedOnEmpty() || dailyCandleRepository.count() > 0) {
			return 0;
		}
		List<DailyCandle> candles = read();
		Set<String> codes = new HashSet<>();
		for (DailyCandle candle : candles) {
			codes.add(candle.getStockCode());
		}
		Set<String> known = new HashSet<>();
		for (Stock stock : stockRepository.findAllById(codes)) {
			known.add(stock.getStockCode());
		}
		List<DailyCandle> insertable = candles.stream().filter(c -> known.contains(c.getStockCode())).toList();
		dailyCandleRepository.saveAll(insertable);
		log.info("일봉 시드 적재 rows={} skipped(종목 없음)={}", insertable.size(), candles.size() - insertable.size());
		return insertable.size();
	}

	private static List<DailyCandle> read() {
		List<DailyCandle> candles = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(new ClassPathResource(PATH).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#") || line.startsWith("stock_code,")) {
					continue;
				}
				String[] c = line.split(",", -1);
				candles.add(DailyCandle.of(c[0], LocalDate.parse(c[1]), Long.parseLong(c[2]), Long.parseLong(c[3]),
					Long.parseLong(c[4]), Long.parseLong(c[5]), Long.parseLong(c[6])));
			}
		} catch (IOException e) {
			throw new IllegalStateException(PATH + " 를 읽지 못했다", e);
		}
		return candles;
	}
}
