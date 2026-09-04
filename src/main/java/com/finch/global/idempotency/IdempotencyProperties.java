package com.finch.global.idempotency;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 멱등성 필터의 설정 ({@code finch.idempotency}).
 *
 * @param paths         {@code Idempotency-Key} 를 요구할 경로. 여기 없는 경로는 필터가 통째로 건너뛴다.
 *                      전 경로에 거는 대신 목록으로 두는 이유 — 멱등성 처리는 요청 본문을 통째로 읽고
 *                      응답을 메모리에 담았다가 다시 쓴다. 조회 요청까지 그 비용을 낼 이유가 없다.
 * @param inProgressTtl "처리 중" 표시의 수명. 짧으면 처리가 끝나기 전에 표시가 풀려 같은 요청이 두 번
 *                      실행되고, 길면 서버가 처리 도중 죽었을 때 그 시간만큼 재시도가 막힌다.
 * @param doneTtl       최초 응답을 재생해 주는 기간. 24시간은 apiSpec 1.4 가 확정한 값이다.
 * @param retryAfter    {@code 409 IDEMPOTENCY_IN_PROGRESS} 에 실어 보낼 {@code Retry-After} 값.
 *                      계약이 "짧게 대기 후 동일 키로 재시도"(apiSpec 1.4)인데 그 "짧게"를 클라이언트가
 *                      추측하면 파트마다 다른 값을 고르게 된다. 서버가 정해서 헤더로 내려준다.
 */
@ConfigurationProperties("finch.idempotency")
public record IdempotencyProperties(
	@DefaultValue List<String> paths,
	@DefaultValue("60s") Duration inProgressTtl,
	@DefaultValue("24h") Duration doneTtl,
	@DefaultValue("1s") Duration retryAfter
) {

	/**
	 * {@code Retry-After} 는 <b>초 단위 정수</b>만 싣는다 (RFC 9110 §10.2.3).
	 * 1초 미만으로 설정해도 0 이 나가지 않게 최소 1 로 올린다 — 0 은 "즉시 재시도"라 되풀이를 부른다.
	 */
	public long retryAfterSeconds() {
		return Math.max(1, retryAfter.toSeconds());
	}
}
