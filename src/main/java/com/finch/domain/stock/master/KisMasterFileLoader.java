package com.finch.domain.stock.master;

import com.finch.domain.stock.StockProperties;
import com.finch.domain.stock.entity.Market;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * KIS 가 배포하는 종목 마스터 zip 두 개를 내려받아 파싱한다. <b>무인증</b>이다 — 앱키·토큰 없이 받는 정적 파일이라
 * KIS 실공급자(S10)와 별개이고, 그래서 S5 가 S10 앞에 올 수 있다.
 * <p>
 * {@code finch.stock.master.source=kis} 일 때만 빈이 된다. 테스트·오프라인(csv)에서는 아예 없고, {@code StockMasterSyncService} 는
 * {@code ObjectProvider} 로 있으면 쓴다. 실패(네트워크·포맷)는 {@link StockMasterLoadException} 하나로 모아 호출자가 폴백한다.
 * <p>
 * 파일이 ~120KB 라 메모리에 통째로 받는다. 코덱 상한(기본 256KB)을 2MB 로 올려 둔 것은 파일이 커져도 여기서 막히지 않게 하기 위해서다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finch.stock.master.source", havingValue = "kis", matchIfMissing = true)
public class KisMasterFileLoader implements StockMasterLoader {

	private final WebClient client;
	private final StockProperties properties;

	public KisMasterFileLoader(WebClient.Builder builder, StockProperties properties) {
		this.properties = properties;
		this.client = builder
			.baseUrl(properties.master().baseUrl())
			.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
			.build();
	}

	@Override
	public List<StockMasterRow> load() {
		List<StockMasterRow> rows = new ArrayList<>();
		for (Market market : Market.values()) {
			byte[] zip = download(market);
			byte[] content = unzip(zip, market);
			List<StockMasterRow> parsed = KisMasterFileParser.parse(content, KisMasterFileParser.Layout.of(market));
			log.info("KIS 마스터 {} 읽음 rows={} bytes={}", market, parsed.size(), content.length);
			rows.addAll(parsed);
		}
		return rows;
	}

	@Override
	public String sourceName() {
		return "KIS";
	}

	@Override
	public boolean complete() {
		return true;
	}

	private byte[] download(Market market) {
		String file = market.name().toLowerCase() + "_code.mst.zip";
		try {
			byte[] body = client.get().uri("/" + file)
				.retrieve()
				.bodyToMono(byte[].class)
				.block(properties.master().timeout());
			if (body == null || body.length == 0) {
				throw new StockMasterLoadException(file + " 응답이 비어 있다");
			}
			return body;
		} catch (StockMasterLoadException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new StockMasterLoadException(file + " 다운로드 실패: " + e.getMessage(), e);
		}
	}

	/** zip 안에 파일이 하나다 — 첫 엔트리만 읽는다. */
	private static byte[] unzip(byte[] zip, Market market) {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry = in.getNextEntry();
			if (entry == null) {
				throw new StockMasterLoadException(market + " 마스터 zip 이 비어 있다");
			}
			return in.readAllBytes();
		} catch (IOException e) {
			throw new StockMasterLoadException(market + " 마스터 zip 을 풀지 못했다: " + e.getMessage(), e);
		}
	}
}
