package com.finch.global.paging;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 커서 페이징 목록의 공통 응답 모양 (apiSpec 1.5).
 * <p>
 * 목록 API 는 전부 이 타입으로 응답한다. 엔드포인트마다 {@code items} 만 다르고 나머지는 같아야
 * 프론트가 무한 스크롤 훅을 하나만 만든다.
 *
 * @param nextCursor 다음 페이지를 요청할 때 그대로 되돌려 보낼 값. <b>마지막 페이지면 null</b> 이다.
 * @param hasNext    {@code nextCursor != null} 과 같은 뜻이지만 함께 내려준다 — 프론트가 "커서가
 *                   null 인지"를 판단 로직에 쓰지 않게 하려는 것이다 (커서는 불투명 문자열이다).
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {

	/**
	 * 요청한 {@code size} 보다 한 건 더 읽어 온 결과를 페이지로 자른다.
	 * <p>
	 * <b>{@code size + 1} 로 조회하는 것이 규칙이다.</b> 별도로 {@code COUNT(*)} 를 세지 않는 이유 —
	 * 다음 페이지가 있는지만 알면 되는데 전체 개수를 세는 것은 목록이 길수록 비싸지고, 세는 시점과
	 * 읽는 시점 사이에 행이 늘면 값이 서로 맞지 않는다. 한 건 더 읽어 보는 쪽은 그 두 문제가 없다.
	 *
	 * @param rows 서비스가 {@code size + 1} 건까지 읽어 온 행. 정렬은 {@code id DESC} 다.
	 * @param size 클라이언트가 요청한 페이지 크기.
	 * @param idOf 행에서 커서로 쓸 id 를 꺼내는 함수.
	 */
	public static <T> CursorPage<T> of(List<T> rows, int size, ToLongFunction<T> idOf, CursorCodec codec) {
		boolean hasNext = rows.size() > size;
		List<T> items = hasNext ? rows.subList(0, size) : rows;
		// 커서는 이 페이지의 마지막 행 id 다. 다음 요청은 "이 id 보다 작은 것"을 읽는다.
		String nextCursor = hasNext ? codec.encode(idOf.applyAsLong(items.getLast())) : null;
		return new CursorPage<>(items, nextCursor, hasNext);
	}

	/** 읽을 것이 없는 목록. 빈 배열과 null 커서는 "마지막 페이지"와 같은 모양이다. */
	public static <T> CursorPage<T> empty() {
		return new CursorPage<>(List.of(), null, false);
	}
}
