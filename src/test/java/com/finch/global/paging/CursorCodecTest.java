package com.finch.global.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.global.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 커서는 클라이언트가 그대로 되돌려 보내는 값이라 <b>왕복이 깨지지 않는 것</b>이 전부다 (apiSpec 1.5).
 * 깨진 값이 들어왔을 때의 처리도 함께 고정한다 — 조용히 넘어가면 무한 스크롤이 같은 페이지를 반복한다.
 */
class CursorCodecTest {

	private final CursorCodec codec = new CursorCodec(JsonMapper.builder().build());

	@ParameterizedTest(name = "id={0}")
	@DisplayName("인코딩한 커서를 다시 읽으면 같은 id 다")
	@ValueSource(longs = {1L, 101L, Long.MAX_VALUE})
	void roundTrip(long id) {
		assertThat(codec.decode(codec.encode(id))).isEqualTo(id);
	}

	/**
	 * apiSpec 1.5 의 응답 예시가 {@code eyJpZCI6MTAxfQ==} 다. 인코딩 방식은 서버 구현 상세지만,
	 * 문서에 실린 값과 실제가 다르면 프론트가 예시를 보고 만든 목 데이터가 400 을 받는다.
	 */
	@Test
	@DisplayName("커서는 Base64(JSON) 이고 명세 예시와 같은 문자열을 만든다")
	void matchesSpecExample() {
		String cursor = codec.encode(101L);

		assertThat(cursor).isEqualTo("eyJpZCI6MTAxfQ==");
		assertThat(new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8)).isEqualTo("{\"id\":101}");
	}

	@ParameterizedTest(name = "\"{0}\"")
	@DisplayName("깨진 커서는 INVALID_REQUEST — 첫 페이지로 되돌리지 않는다")
	@ValueSource(strings = {
		"not-base64!!",              // Base64 형식 위반
		"aGVsbG8=",                  // Base64 는 맞지만 JSON 이 아니다 ("hello")
		"eyJmb28iOjF9",              // JSON 이지만 id 가 없다 ({"foo":1})
		"",                          // 빈 문자열
	})
	void rejectsCorruptedCursor(String cursor) {
		assertThatThrownBy(() -> codec.decode(cursor))
			.isInstanceOf(CustomException.class)
			.satisfies(e -> assertThat(((CustomException) e).getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));
	}
}
