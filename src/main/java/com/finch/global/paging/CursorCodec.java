package com.finch.global.paging;

import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 커서 문자열과 마지막으로 읽은 {@code id} 를 서로 바꾼다 (apiSpec 1.5).
 * <p>
 * 커서는 <b>불투명 문자열</b>이다 — 클라이언트는 파싱하지 않고 받은 값을 그대로 되돌려 보내기로 되어
 * 있고(프론트 contracts C28), 인코딩 방식은 서버 구현 상세다. 그래서 Base64 로 감싼다. 숫자를 그대로
 * 내보내지 않는 이유는 성능이 아니라 <b>계약</b>이다: 눈에 보이는 값이면 클라이언트가 언젠가 그것을
 * 계산하기 시작하고, 그 순간 정렬 기준을 바꿀 수 없게 된다.
 * <p>
 * 담는 것은 {@code id} 하나다. 정렬이 어느 목록에서나 {@code id DESC} 로 고정이라(apiSpec 1.5,
 * "최신순") 동점이 없고, 동점이 없으면 복합 커서가 필요 없다. 정렬 기준이 늘어나면 이 레코드에 필드를
 * 더한다 — 그때도 Base64 안이라 클라이언트 코드는 바뀌지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CursorCodec {

	private final ObjectMapper objectMapper;

	/**
	 * JSON 한 겹을 두는 이유 — 필드가 늘어도 이전 커서를 계속 읽을 수 있다. 숫자만 넣으면 그게 안 된다.
	 * <p>
	 * {@code id} 를 {@code long} 이 아니라 {@code Long} 으로 받는다. 원시 타입이면 {@code id} 가 없는
	 * JSON 이 <b>0 으로 조용히 채워져</b> "첫 페이지부터 다시"가 아니라 "빈 목록"이 나간다.
	 * 박싱해 두면 null 로 구분되어 아래에서 400 으로 끊을 수 있다.
	 */
	private record Cursor(Long id) {
	}

	/** 이 페이지의 마지막 행 id 로 다음 커서를 만든다. */
	public String encode(long id) {
		byte[] json = objectMapper.writeValueAsString(new Cursor(id)).getBytes(StandardCharsets.UTF_8);
		return Base64.getEncoder().encodeToString(json);
	}

	/**
	 * 클라이언트가 돌려준 커서를 읽는다.
	 * <p>
	 * <b>깨진 커서는 {@code INVALID_REQUEST} 다.</b> 조용히 첫 페이지로 되돌리지 않는다 — 그러면
	 * 무한 스크롤이 같은 목록을 영원히 반복하고, 원인이 클라이언트 버그인데 서버가 정상 응답을 준다.
	 * 400 으로 끊으면 잘못된 값을 만든 쪽에서 바로 드러난다.
	 */
	public long decode(String cursor) {
		try {
			byte[] json = Base64.getDecoder().decode(cursor);
			Long id = objectMapper.readValue(new String(json, StandardCharsets.UTF_8), Cursor.class).id();
			if (id == null) {
				throw new IllegalArgumentException("id 가 없는 커서");
			}
			return id;
		} catch (RuntimeException e) {
			// Base64 형식 위반(IllegalArgumentException)과 JSON 파싱 실패를 한 갈래로 묶는다.
			// 클라이언트 입장에서 둘 다 "우리가 준 적 없는 값을 보냈다"로 같은 잘못이다.
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST);
		}
	}
}
