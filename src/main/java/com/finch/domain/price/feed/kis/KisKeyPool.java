package com.finch.domain.price.feed.kis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 앱키 풀. 설정의 {@code finch.kis.keys[]} 를 검증해 들고, 호출을 키에 배정한다.
 * <p>
 * 배정 규칙은 둘이다.
 * <ul>
 *   <li>{@link #partition} — 순회 대상 종목을 키 수로 나눈다(라운드로빈). 폴링이 매 틱 쓴다. 키가 하나면 전부 그 키다.</li>
 *   <li>{@link #next} — 단발 호출(일봉 백필)에 키 하나를 돌아가며 준다.</li>
 * </ul>
 * 키별 호출 한도는 여기서 세지 않는다 — 그건 {@code KisClient} 의 리미터가 키 단위로 한다. 이 클래스는 "누가 어느 종목을 맡나" 만 정한다.
 * <p>
 * 빈이 아니라 {@code KisProperties} 에서 만든 값 객체다. provider=fake 면 만들어지지 않는다.
 */
public final class KisKeyPool {

	private final List<KisCredential> keys;
	private final AtomicInteger cursor = new AtomicInteger();

	public KisKeyPool(List<KisCredential> configured) {
		if (configured == null || configured.isEmpty()) {
			throw new IllegalStateException("finch.kis.keys 가 비어 있다. provider=kis 면 앱키가 1개 이상 필요하다");
		}
		List<KisCredential> labeled = new ArrayList<>(configured.size());
		Set<String> labels = new HashSet<>();
		for (int i = 0; i < configured.size(); i++) {
			KisCredential key = configured.get(i).withDefaultLabel(i);
			if (!labels.add(key.label())) {
				throw new IllegalStateException("finch.kis.keys 의 label 이 겹친다: " + key.label());
			}
			labeled.add(key);
		}
		this.keys = List.copyOf(labeled);
	}

	public List<KisCredential> keys() {
		return keys;
	}

	public int size() {
		return keys.size();
	}

	/** 단발 호출용. 부를 때마다 다음 키다. */
	public KisCredential next() {
		return keys.get(Math.floorMod(cursor.getAndIncrement(), keys.size()));
	}

	/**
	 * 종목을 키 수로 나눈다. 순서를 유지한 라운드로빈이라 종목 수가 키 수로 나누어떨어지지 않아도 차이는 최대 1이다.
	 * 종목이 없는 키는 결과에 들어가지 않는다 — 호출자가 빈 목록을 돌지 않게.
	 */
	public Map<KisCredential, List<String>> partition(List<String> stockCodes) {
		Map<KisCredential, List<String>> result = new LinkedHashMap<>();
		for (int i = 0; i < stockCodes.size(); i++) {
			result.computeIfAbsent(keys.get(i % keys.size()), k -> new ArrayList<>()).add(stockCodes.get(i));
		}
		return result;
	}
}
