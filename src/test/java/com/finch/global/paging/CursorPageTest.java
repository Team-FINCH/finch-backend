package com.finch.global.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code size + 1} 로 읽어 온 결과를 페이지로 자르는 규칙을 고정한다. 목록 API 가 전부 이 한 곳을 쓰므로
 * 여기가 틀리면 <b>모든 목록에서 한 건이 새거나 빠진다.</b>
 */
class CursorPageTest {

	private final CursorCodec codec = new CursorCodec(JsonMapper.builder().build());

	@Test
	@DisplayName("size 보다 한 건 더 읽혔으면 그 한 건은 버리고 hasNext 를 켠다")
	void trimsExtraRow() {
		CursorPage<Long> page = CursorPage.of(ids(1, 4), 3, Long::longValue, codec);

		assertThat(page.items()).containsExactly(1L, 2L, 3L);
		assertThat(page.hasNext()).isTrue();
		// 다음 커서는 버린 4번이 아니라 이 페이지의 마지막인 3번이다. 4번을 담으면 그 행을 건너뛴다.
		assertThat(codec.decode(page.nextCursor())).isEqualTo(3L);
	}

	@Test
	@DisplayName("정확히 size 만큼 읽혔으면 마지막 페이지다 — nextCursor 는 null")
	void lastPageHasNoCursor() {
		CursorPage<Long> page = CursorPage.of(ids(1, 3), 3, Long::longValue, codec);

		assertThat(page.items()).containsExactly(1L, 2L, 3L);
		assertThat(page.hasNext()).isFalse();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	@DisplayName("읽은 것이 없으면 빈 목록이고 마지막 페이지다")
	void emptyResultIsLastPage() {
		CursorPage<Long> page = CursorPage.of(List.of(), 3, Long::longValue, codec);

		assertThat(page.items()).isEmpty();
		assertThat(page.hasNext()).isFalse();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	@DisplayName("empty() 는 조회를 건너뛴 경우에도 같은 모양을 준다")
	void emptyFactoryMatchesLastPageShape() {
		CursorPage<Long> page = CursorPage.empty();

		assertThat(page.items()).isEmpty();
		assertThat(page.hasNext()).isFalse();
		assertThat(page.nextCursor()).isNull();
	}

	/** {@code from} 부터 {@code to} 까지의 id 목록. 실제 조회는 id DESC 지만 자르는 규칙은 순서와 무관하다. */
	private static List<Long> ids(long from, long to) {
		return LongStream.rangeClosed(from, to).boxed().toList();
	}
}
