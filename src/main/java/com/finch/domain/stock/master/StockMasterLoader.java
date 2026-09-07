package com.finch.domain.stock.master;

import java.util.List;

/**
 * 종목 마스터를 어디선가 읽어 온다. 구현은 둘 — KIS 마스터 파일({@link KisMasterFileLoader})과 CSV 시드({@link CsvSeedLoader}).
 * <p>
 * 인터페이스로 가른 이유 — 동기화 로직(UPSERT·비활성화)은 출처와 무관해야 하고, 테스트·오프라인에서는 네트워크 없이 돌아야 한다.
 * 파일 포맷이 바뀌어 KIS 파싱이 실패해도 서비스 기동이 막히지 않게 CSV 로 폴백한다 (backend_story S5).
 */
public interface StockMasterLoader {

	/**
	 * @throws StockMasterLoadException 읽거나 파싱하지 못했을 때. 호출자가 폴백을 정한다.
	 */
	List<StockMasterRow> load();

	/** 로그·동기화 결과에 남길 이름. */
	String sourceName();

	/**
	 * true 면 전 종목 목록이라 "여기 없는 종목 = 상장폐지"로 볼 수 있다. CSV 시드는 일부 종목뿐이라 false 다 —
	 * 시드로 동기화하면서 나머지를 전부 비활성화하면 안 된다.
	 */
	boolean complete();
}
