package com.finch.domain.stock.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.master.KisMasterFileParser.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 고정폭 파서를 <b>실제 파일에서 떼어 낸 행</b>으로 고정한다 (2026-09-07 KIS 마스터). 픽스처는 바이트 그대로다 — EUC-KR 이라
 * 텍스트로 편집하면 깨진다. 코스피 3행: 삼성전자 · KR모터스(거래정지) · ETF(제외 대상). 코스닥 3행: 에코프로비엠 · 신라섬유(거래정지+관리종목) ·
 * 딥커머스(외국주, ISIN HK — 주권이라 포함).
 * <p>
 * 오프셋이 틀리면 여기서 잡힌다. 삼성전자 기준가 255,500 과 에코프로비엠 106,000 은 그날 파일의 값이고, 오프셋이 한 바이트만 밀려도 다른 숫자가 된다.
 */
class KisMasterFileParserTest {

	@Test
	@DisplayName("코스피 — 주권 2행을 읽고 ETF 는 제외한다. 코드·이름·기준가·거래정지가 제자리다")
	void parsesKospiSample() throws IOException {
		List<StockMasterRow> rows = KisMasterFileParser.parse(fixture("kis-sample-kospi.mst"), Layout.KOSPI);

		assertThat(rows).hasSize(2);
		StockMasterRow samsung = rows.get(0);
		assertThat(samsung.stockCode()).isEqualTo("005930");
		assertThat(samsung.stockName()).isEqualTo("삼성전자");
		assertThat(samsung.market()).isEqualTo(Market.KOSPI);
		assertThat(samsung.suspended()).isFalse();
		assertThat(samsung.suspendedReason()).isNull();
		assertThat(samsung.referencePrice()).isEqualTo(255_500L);

		StockMasterRow suspended = rows.get(1);
		assertThat(suspended.stockCode()).isEqualTo("000040");
		assertThat(suspended.stockName()).isEqualTo("KR모터스");
		assertThat(suspended.suspended()).isTrue();
		assertThat(suspended.suspendedReason()).contains("거래정지");
	}

	@Test
	@DisplayName("코스닥 — 오프셋이 코스피와 다르다. 관리종목이면 사유에 남고 외국주권(FS, HK ISIN)도 포함한다")
	void parsesKosdaqSample() throws IOException {
		List<StockMasterRow> rows = KisMasterFileParser.parse(fixture("kis-sample-kosdaq.mst"), Layout.KOSDAQ);

		assertThat(rows).hasSize(3);
		assertThat(rows.get(0).stockCode()).isEqualTo("247540");
		assertThat(rows.get(0).stockName()).isEqualTo("에코프로비엠");
		assertThat(rows.get(0).market()).isEqualTo(Market.KOSDAQ);
		assertThat(rows.get(0).suspended()).isFalse();
		assertThat(rows.get(0).referencePrice()).isEqualTo(106_000L);

		assertThat(rows.get(1).stockName()).isEqualTo("신라섬유");
		assertThat(rows.get(1).suspended()).isTrue();
		assertThat(rows.get(1).suspendedReason()).isEqualTo("관리종목 · 거래정지");

		assertThat(rows.get(2).stockCode()).isEqualTo("900110");
		assertThat(rows.get(2).stockName()).isEqualTo("딥커머스");
	}

	/**
	 * KIS 가 파일을 CRLF 로 내보내도 줄 길이 검사가 깨지면 안 된다.
	 * <p>
	 * <b>픽스처를 그대로 쓰지 않고 LF 로 한 번 정규화한 뒤 CRLF 를 만든다.</b> 그러지 않으면 git 이 체크아웃하며 픽스처를 이미
	 * CRLF 로 바꿔 둔 경우 {@code \r\r\n} 이 되어 289 바이트가 된다 — 실제로 그렇게 실패했다. 픽스처 자체는 {@code .gitattributes}
	 * 의 {@code *.mst binary} 로 고정했고, 이 테스트는 그 설정이 빠져도 원인이 드러나게 스스로 정규화한다.
	 */
	@Test
	@DisplayName("CRLF 개행도 같은 결과다")
	void toleratesCrlf() throws IOException {
		String normalized = new String(fixture("kis-sample-kospi.mst"), StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
		byte[] lf = normalized.getBytes(StandardCharsets.ISO_8859_1);
		byte[] crlf = normalized.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1);

		assertThat(KisMasterFileParser.parse(crlf, Layout.KOSPI))
			.usingRecursiveComparison().isEqualTo(KisMasterFileParser.parse(lf, Layout.KOSPI));
	}

	/** 포맷이 바뀌면 오프셋이 전부 밀린다. 엉뚱한 값을 조용히 넣는 대신 예외로 끊고 호출자가 시드로 폴백한다. */
	@Test
	@DisplayName("줄 길이가 레이아웃과 다르면 포맷 변경으로 보고 예외다")
	void rejectsUnexpectedLineLength() throws IOException {
		byte[] kospi = fixture("kis-sample-kospi.mst");

		// 코스피 파일을 코스닥 레이아웃(282)으로 읽으면 첫 줄에서 끊긴다.
		assertThatThrownBy(() -> KisMasterFileParser.parse(kospi, Layout.KOSDAQ))
			.isInstanceOf(StockMasterLoadException.class)
			.hasMessageContaining("282");
		// 한 바이트 잘린 줄도 마찬가지다.
		byte[] truncated = Arrays.copyOf(kospi, 287);
		assertThatThrownBy(() -> KisMasterFileParser.parse(truncated, Layout.KOSPI))
			.isInstanceOf(StockMasterLoadException.class);
	}

	@Test
	@DisplayName("주권이 한 건도 없으면 예외다 — 파일은 읽혔지만 내용이 다르다")
	void rejectsFileWithoutStocks() throws IOException {
		// 셋째 줄(ETF)만 남긴다. 줄 수로 자른다 — 바이트 오프셋으로 자르면 줄바꿈이 CRLF 일 때 어긋난다.
		String[] lines = new String(fixture("kis-sample-kospi.mst"), StandardCharsets.ISO_8859_1)
			.replace("\r\n", "\n").split("\n");
		byte[] etfOnly = (lines[2] + "\n").getBytes(StandardCharsets.ISO_8859_1);

		assertThatThrownBy(() -> KisMasterFileParser.parse(etfOnly, Layout.KOSPI))
			.isInstanceOf(StockMasterLoadException.class)
			.hasMessageContaining("주권");
	}

	private static byte[] fixture(String name) throws IOException {
		return new ClassPathResource("stock/" + name).getContentAsByteArray();
	}
}
