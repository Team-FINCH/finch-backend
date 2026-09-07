package com.finch.domain.price.feed.kis;

import org.springframework.util.StringUtils;

/**
 * KIS 앱키 하나. {@code finch.kis.keys[]} 의 항목이고 <b>풀의 단위</b>다 — 토큰·초당 리미터·메트릭 태그가 전부 이 단위로 나뉜다.
 * <p>
 * {@code label} 이 있는 이유 — 앱키 원문은 비밀값이라 로그·메트릭·Redis 키 어디에도 찍지 않는다. 사람이 읽을 이름이 따로 있어야
 * "어느 키가 429 를 맞았나" 를 대시보드에서 볼 수 있다. 생략하면 {@code key-{순번}} 이다.
 *
 * @param label 로그·메트릭·Redis 키에 쓰는 이름. 유일해야 한다 ({@link KisKeyPool} 이 검사한다).
 */
public record KisCredential(String appKey, String appSecret, String label) {

	public KisCredential {
		if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
			throw new IllegalArgumentException("KIS 앱키·시크릿이 비어 있다. finch.kis.keys[] 항목을 확인한다");
		}
	}

	/** 순번으로 기본 이름을 붙인 사본. 설정에 {@code label} 이 없을 때 {@link KisKeyPool} 이 쓴다. */
	KisCredential withDefaultLabel(int index) {
		return StringUtils.hasText(label) ? this : new KisCredential(appKey, appSecret, "key-" + index);
	}

	/** 앱키 원문이 실수로 로그에 찍히지 않게 한다. */
	@Override
	public String toString() {
		return "KisCredential[" + label + "]";
	}
}
