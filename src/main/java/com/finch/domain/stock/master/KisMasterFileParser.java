package com.finch.domain.stock.master;

import com.finch.domain.stock.entity.Market;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * KIS 종목 마스터 파일({@code kospi_code.mst}·{@code kosdaq_code.mst})의 고정폭 파서. 네트워크·DB 를 모르는 순수 함수라
 * 실제 파일에서 떼어 낸 행으로 테스트한다 ({@code src/test/resources/stock/kis-sample-*.mst}).
 * <p>
 * <b>바이트 단위 고정폭이다.</b> EUC-KR 이라 한글 한 글자가 2바이트이고, 문자 단위로 자르면 종목명 뒤의 모든 필드가 밀린다.
 * 그래서 줄을 바이트 배열로 다루고 이름만 EUC-KR 로 디코딩한다.
 * <p>
 * 레이아웃 (2026-09-07 실제 파일로 확인 — 삼성전자 상장일 19750611·액면가 100, 에코프로비엠 상장일 20190305·97,830천주가
 * 정확한 자리에 나온다):
 * <pre>
 *   앞 61바이트  단축코드 9 · 표준코드 12 · 한글명 40 (공백 채움)
 *   뒤 227(코스피) / 221(코스닥) 바이트  그룹코드 2 · 시총규모 1 · 업종 4+4+4 · 플래그들 · 기준가 9 · 매매단위 5 · 시간외 5 ·
 *   거래정지 1 · 정리매매 1 · 관리종목 1 · …
 *   → 한 줄 288 / 282 바이트
 * </pre>
 * 기준가와 거래정지의 오프셋이 시장마다 달라 {@link Layout} 으로 나눈다. <b>줄 길이가 레이아웃과 다르면 포맷이 바뀐 것</b>으로
 * 보고 예외를 던진다 — 밀린 오프셋으로 엉뚱한 값을 조용히 넣는 것보다 낫고, 호출자는 CSV 시드로 폴백한다.
 * <p>
 * <b>그룹코드 {@code ST}(주권)·{@code FS}(외국주권)만 종목으로 본다.</b> ETF·ETN(EF·EN)·수익증권(BC)·리츠(RT) 등은 넣지 않는다 —
 * featureSpec 4장의 "국내 상장 종목(코스피/코스닥)"은 주식이고, 코스피 파일의 절반 이상이 ETF 라 검색 자동완성이 ETF 로 덮인다.
 * 외국주권은 코스닥에 상장된 해외 법인 주식(11건)이라 매매 대상이다.
 */
public final class KisMasterFileParser {

	private static final Charset EUC_KR = Charset.forName("EUC-KR");
	private static final Pattern STOCK_CODE = Pattern.compile("[0-9A-Z]{6}");
	private static final java.util.Set<String> STOCK_GROUPS = java.util.Set.of("ST", "FS");
	private static final int PREFIX_LENGTH = 61;

	/**
	 * 시장별 오프셋 (줄 시작 기준 0-based 바이트). 뒤쪽 필드 묶음의 길이가 코스피 227·코스닥 221 로 달라 값이 다르다.
	 *
	 * @param lineLength      한 줄 바이트 수. 개행 제외.
	 * @param referencePrice  기준가 9자리 시작.
	 * @param suspended       거래정지 플래그(Y/N).
	 * @param administrative  관리종목 플래그(Y/N).
	 */
	public record Layout(Market market, int lineLength, int referencePrice, int suspended, int administrative) {

		public static final Layout KOSPI = new Layout(Market.KOSPI, 288, 102, 121, 123);
		public static final Layout KOSDAQ = new Layout(Market.KOSDAQ, 282, 97, 116, 118);

		public static Layout of(Market market) {
			return market == Market.KOSPI ? KOSPI : KOSDAQ;
		}
	}

	private KisMasterFileParser() {
	}

	/**
	 * @param content 압축을 푼 파일 전체.
	 * @throws StockMasterLoadException 줄 길이가 레이아웃과 다르거나 주권이 하나도 없을 때.
	 */
	public static List<StockMasterRow> parse(byte[] content, Layout layout) {
		List<StockMasterRow> rows = new ArrayList<>();
		int lineNo = 0;
		int start = 0;
		for (int i = 0; i <= content.length; i++) {
			if (i < content.length && content[i] != '\n') {
				continue;
			}
			int end = i;
			if (end > start && content[end - 1] == '\r') {
				end--;
			}
			if (end > start) {
				lineNo++;
				byte[] line = Arrays.copyOfRange(content, start, end);
				if (line.length != layout.lineLength()) {
					throw new StockMasterLoadException(layout.market() + " 마스터 " + lineNo + "번째 줄 길이가 "
						+ line.length + " 바이트다 (기대 " + layout.lineLength() + "). 파일 포맷이 바뀐 것으로 본다");
				}
				StockMasterRow row = parseLine(line, layout);
				if (row != null) {
					rows.add(row);
				}
			}
			start = i + 1;
		}
		if (rows.isEmpty()) {
			throw new StockMasterLoadException(layout.market() + " 마스터에서 주권(ST·FS)을 한 건도 읽지 못했다. 파일 포맷이 바뀐 것으로 본다");
		}
		return rows;
	}

	/** 주권이 아니거나 코드 모양이 다르면 null. */
	static StockMasterRow parseLine(byte[] line, Layout layout) {
		String group = ascii(line, PREFIX_LENGTH, 2);
		if (!STOCK_GROUPS.contains(group)) {
			return null;
		}
		String code = ascii(line, 0, 9).trim();
		if (!STOCK_CODE.matcher(code).matches()) {
			return null;
		}
		String name = new String(line, 21, 40, EUC_KR).trim();
		boolean suspended = line[layout.suspended()] == 'Y';
		boolean administrative = line[layout.administrative()] == 'Y';
		String reason = !suspended ? null : administrative ? "관리종목 · 거래정지" : "거래정지";
		Long referencePrice = digits(ascii(line, layout.referencePrice(), 9));
		return new StockMasterRow(code, name, layout.market(), suspended, reason, referencePrice);
	}

	private static String ascii(byte[] line, int offset, int length) {
		return new String(line, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
	}

	/** 9자리 0 채움 숫자. 0 이나 숫자가 아니면 null — 기준가 없음. */
	private static Long digits(String field) {
		String trimmed = field.trim();
		if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) {
			return null;
		}
		long value = Long.parseLong(trimmed);
		return value > 0 ? value : null;
	}
}
