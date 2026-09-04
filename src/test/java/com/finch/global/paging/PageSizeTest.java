package com.finch.global.paging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * apiSpec 1.5 의 기본값·상한을 고정한다. 이 숫자는 프론트 contracts C27 에도 적혀 있어서
 * 한쪽만 바뀌면 목록 길이에 대한 두 파트의 기대가 갈린다.
 * <p>
 * 여기 있는 것은 <b>마지막 방어선</b>이다. 공개 API 컨트롤러는 {@code @Min(1) @Max(100)} 으로 범위 밖
 * 값을 400 으로 거절한다 (PageSize 주석) — 그래서 클램프가 실제로 도는 것은 검증이 없는 내부 호출자다.
 */
class PageSizeTest {

	@Test
	@DisplayName("공개 API 는 기본 30, 내부 연동 API 는 기본 100")
	void defaults() {
		assertThat(PageSize.forPublic(null)).isEqualTo(30);
		assertThat(PageSize.forInternal(null)).isEqualTo(100);
	}

	@ParameterizedTest(name = "요청 {0} → {1}")
	@DisplayName("공개 API 는 1~100 으로 자른다")
	@CsvSource({
		"1, 1",
		"30, 30",
		"100, 100",
		"101, 100",
		"1000, 100",
		"0, 1",
		"-5, 1",
	})
	void clampsPublicSize(int requested, int expected) {
		assertThat(PageSize.forPublic(requested)).isEqualTo(expected);
	}

	@Test
	@DisplayName("내부 연동 API 의 상한도 100 이다 — 기본값만 다르고 상한은 같다")
	void clampsInternalSize() {
		assertThat(PageSize.forInternal(101)).isEqualTo(100);
		assertThat(PageSize.forInternal(50)).isEqualTo(50);
		assertThat(PageSize.forInternal(0)).isEqualTo(1);
	}
}
