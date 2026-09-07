package com.finch.domain.ledger.controller;

import com.finch.domain.ledger.dto.request.TransactionFilter;
import com.finch.domain.ledger.dto.response.TransactionRes;
import com.finch.domain.ledger.service.TransactionQueryService;
import com.finch.global.paging.CursorPage;
import com.finch.global.paging.PageSize;
import com.finch.global.security.LoginUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매매 내역 API (apiSpec 8.2). 계좌 식별자를 받지 않는다 — 계좌는 토큰의 사용자로 찾는다 (apiSpec 1.6).
 * <p>
 * 파라미터 검증은 전부 공통 계층이 {@code INVALID_REQUEST} 로 답한다 (apiSpec 11.1·11.2) — {@code type} 열거값 밖은
 * 파라미터 변환 실패, {@code size} 범위 밖은 {@code @Min·@Max} 위반, 손상된 {@code cursor} 는 {@code CursorCodec}.
 * 이 엔드포인트의 고유 코드는 없다. 내역 없음은 빈 {@code items} 이지 에러가 아니다.
 * <p>
 * {@code size} 에 {@code @Min(1) @Max(100)} 을 붙이는 이유는 {@code PageSize} 주석에 있다 — 범위 밖을 조용히 깎아 정상
 * 응답을 주면 프론트는 요청한 만큼 받은 줄 안다. 클램프는 마지막 방어선이고 검증이 앞이다.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

	private final TransactionQueryService transactionQueryService;

	/**
	 * {@code type} 은 비어 있어도 {@code ALL} 이다 ({@code defaultValue} 는 빈 문자열에도 적용된다).
	 * {@code cursor} 는 빈 문자열이면 첫 페이지로 본다 — 명세 예시가 {@code cursor=} 로 시작한다 (apiSpec 8.2).
	 */
	@GetMapping
	public CursorPage<TransactionRes> list(@LoginUser long userId,
		@RequestParam(defaultValue = "ALL") TransactionFilter type,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) @Min(1) @Max(PageSize.PUBLIC_MAX) Integer size) {
		String pageCursor = cursor == null || cursor.isBlank() ? null : cursor;
		return transactionQueryService.list(userId, type, pageCursor, PageSize.forPublic(size));
	}
}
