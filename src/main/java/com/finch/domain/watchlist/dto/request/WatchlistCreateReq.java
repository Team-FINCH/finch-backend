package com.finch.domain.watchlist.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * `POST /watchlist` 요청 (apiSpec 6.3). 종목코드 하나다.
 * <p>
 * 형식(6자리)까지 여기서 검사하지 않는다. 형식이 틀린 코드는 종목 조회에서 걸려 {@code STOCK_NOT_FOUND} 가 되는데, 그것이
 * "없는 종목"이라는 같은 뜻이고 프론트가 분기를 하나 덜 만든다.
 */
public record WatchlistCreateReq(
	@NotBlank(message = "필수 값입니다") String stockCode
) {
}
