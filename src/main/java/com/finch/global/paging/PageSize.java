package com.finch.global.paging;

/**
 * 페이지 크기의 기본값과 상한 (apiSpec 1.5).
 * <p>
 * 공개 API 는 기본 30·최대 100, 내부 연동 API({@code /internal/v1}) 는 기본·최대 모두 100 이다.
 * 내부 쪽 기본값이 큰 이유는 호출자가 사람이 보는 화면이 아니라 AI 서버라서다 — 분석에 쓸 이력을
 * 한 번에 받아 가는 편이 왕복 횟수를 줄인다.
 * <p>
 * <b>공개 API 컨트롤러는 이 클램프에 기대지 않고 {@code @Min(1) @Max(100)} 을 함께 붙인다.</b>
 * 범위 밖 값은 조용히 고쳐 주는 것이 아니라 {@code INVALID_REQUEST} 로 거절해야 한다(apiSpec 11.1) —
 * {@code size=1000} 을 100 으로 깎아 정상 응답을 주면, 프론트는 1000건을 받은 줄 알고 나머지를
 * 요청하지 않는다. 여기 있는 클램프는 <b>마지막 방어선</b>이고, 검증이 없는 내부 호출자용이다.
 */
public final class PageSize {

	public static final int PUBLIC_DEFAULT = 30;
	public static final int PUBLIC_MAX = 100;
	public static final int INTERNAL_DEFAULT = 100;
	public static final int INTERNAL_MAX = 100;

	private PageSize() {
	}

	/** 공개 API. {@code size} 파라미터는 선택이므로 null 이 올 수 있다. */
	public static int forPublic(Integer requested) {
		return clamp(requested, PUBLIC_DEFAULT, PUBLIC_MAX);
	}

	/** 내부 연동 API. */
	public static int forInternal(Integer requested) {
		return clamp(requested, INTERNAL_DEFAULT, INTERNAL_MAX);
	}

	private static int clamp(Integer requested, int defaultSize, int max) {
		if (requested == null) {
			return defaultSize;
		}
		// 0 이하를 기본값으로 되돌리지 않고 1 로 올린다. "한 건도 안 주는 페이지"는 무한 스크롤을
		// 멈추게 하는데, 기본값으로 되돌리면 요청한 것과 전혀 다른 크기가 나가 원인을 찾기 어렵다.
		return Math.clamp(requested, 1, max);
	}
}
