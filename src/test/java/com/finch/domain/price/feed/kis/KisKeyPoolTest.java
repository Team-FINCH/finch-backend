package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 키 풀의 배정 규칙. 키가 하나일 때와 여럿일 때 둘 다 본다 — "키 하나" 가정이 코드에 없다는 것이 이 스토리의 조건이다. */
class KisKeyPoolTest {

	private static final KisCredential A = new KisCredential("key-a", "secret-a", "a");
	private static final KisCredential B = new KisCredential("key-b", "secret-b", "b");

	@Test
	@DisplayName("키가 하나면 모든 종목이 그 키다")
	void singleKeyTakesAll() {
		KisKeyPool pool = new KisKeyPool(List.of(A));

		Map<KisCredential, List<String>> byKey = pool.partition(List.of("005930", "000660", "035420"));

		assertThat(byKey).containsOnlyKeys(A);
		assertThat(byKey.get(A)).containsExactly("005930", "000660", "035420");
		assertThat(pool.next()).isEqualTo(A);
		assertThat(pool.next()).isEqualTo(A);
	}

	@Test
	@DisplayName("키가 둘이면 종목을 라운드로빈으로 나눈다 — 차이는 최대 1")
	void twoKeysSplitRoundRobin() {
		KisKeyPool pool = new KisKeyPool(List.of(A, B));

		Map<KisCredential, List<String>> byKey = pool.partition(List.of("1", "2", "3", "4", "5"));

		assertThat(byKey.get(A)).containsExactly("1", "3", "5");
		assertThat(byKey.get(B)).containsExactly("2", "4");
		assertThat(pool.next()).isEqualTo(A);
		assertThat(pool.next()).isEqualTo(B);
		assertThat(pool.next()).isEqualTo(A);
	}

	@Test
	@DisplayName("종목이 없는 키는 결과에 들어가지 않는다")
	void skipsIdleKeys() {
		KisKeyPool pool = new KisKeyPool(List.of(A, B));

		assertThat(pool.partition(List.of("1"))).containsOnlyKeys(A);
		assertThat(pool.partition(List.of())).isEmpty();
	}

	@Test
	@DisplayName("label 이 없으면 key-{순번} 이고, 겹치면 기동을 막는다")
	void labelsDefaultAndMustBeUnique() {
		KisKeyPool pool = new KisKeyPool(List.of(new KisCredential("k1", "s1", null), new KisCredential("k2", "s2", "")));
		assertThat(pool.keys()).extracting(KisCredential::label).containsExactly("key-0", "key-1");

		assertThatThrownBy(() -> new KisKeyPool(List.of(A, new KisCredential("k3", "s3", "a"))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("label");
		assertThatThrownBy(() -> new KisKeyPool(List.of()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("앱키 원문은 toString 에 나오지 않는다 — 로그에 새지 않게")
	void toStringHidesSecret() {
		assertThat(A.toString()).doesNotContain("key-a").doesNotContain("secret-a").contains("a");
	}
}
