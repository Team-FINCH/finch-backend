package com.finch;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.domain.ai.exception.AiErrorCode;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.deposit.exception.DepositErrorCode;
import com.finch.domain.order.exception.OrderErrorCode;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.watchlist.exception.WatchlistErrorCode;
import com.finch.global.apiPayload.code.BaseErrorCode;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 백엔드 enum 전체가 apiSpec 11장 목록과 정확히 일치하는지 고정한다.
 * <p>
 * 프론트는 이 문자열로만 분기하고(apiSpec 1.3) 같은 목록을 자기 상수로 옮겨 두었다.
 * 코드 하나가 이름만 바뀌거나 상태가 달라지면 프론트 분기가 컴파일 에러 없이 조용히 죽는다.
 * 그래서 기대값을 여기 다시 적는다 — 명세를 고칠 때 이 표도 같이 고치는 것이 의도된 마찰이다.
 * <p>
 * 새 도메인 enum 을 만들면 ALL_ENUMS 에 추가한다. 빠뜨리면 중복 검사에서 새지만 목록 검사는 통과하므로
 * 도메인 enum 을 추가하는 MR 은 이 파일도 함께 건드려야 한다.
 */
class ErrorCodeContractTest {

	private static final List<BaseErrorCode[]> ALL_ENUMS = List.of(
		GeneralErrorCode.values(),
		AuthErrorCode.values(),
		AiErrorCode.values(),
		DepositErrorCode.values(),
		StockErrorCode.values(),
		WatchlistErrorCode.values(),
		OrderErrorCode.values()
	);

	/** apiSpec 11장을 그대로 옮긴 표. 순서·묶음도 문서와 같다. */
	private static final Map<String, HttpStatus> SPEC_SECTION_11 = Map.ofEntries(
		// 인증
		Map.entry("AUTH_KAKAO_FAILED", HttpStatus.UNAUTHORIZED),
		Map.entry("AUTH_REFRESH_TOKEN_MISSING", HttpStatus.UNAUTHORIZED),
		Map.entry("AUTH_INVALID_TOKEN", HttpStatus.UNAUTHORIZED),
		Map.entry("AUTH_TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED),
		Map.entry("AUTH_FORBIDDEN", HttpStatus.FORBIDDEN),
		// 공통
		Map.entry("INVALID_REQUEST", HttpStatus.BAD_REQUEST),
		Map.entry("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND),
		Map.entry("IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST),
		Map.entry("IDEMPOTENCY_IN_PROGRESS", HttpStatus.CONFLICT),
		Map.entry("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT),
		Map.entry("METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED),
		Map.entry("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
		Map.entry("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR),
		// AI 중계 (백엔드 발행분)
		Map.entry("AI_UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY),
		Map.entry("AI_UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT),
		// 충전
		Map.entry("DEPOSIT_AMOUNT_INVALID", HttpStatus.BAD_REQUEST),
		Map.entry("DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED", HttpStatus.CONFLICT),
		Map.entry("DEPOSIT_LIMIT_EXCEEDED", HttpStatus.CONFLICT),
		// 종목 · 관심 종목
		Map.entry("STOCK_NOT_FOUND", HttpStatus.NOT_FOUND),
		Map.entry("WATCHLIST_LIMIT_EXCEEDED", HttpStatus.CONFLICT),
		Map.entry("WATCHLIST_ALREADY_EXISTS", HttpStatus.CONFLICT),
		// 주문
		Map.entry("ORDER_QUANTITY_INVALID", HttpStatus.BAD_REQUEST),
		Map.entry("ORDER_MARKET_CLOSED", HttpStatus.CONFLICT),
		Map.entry("ORDER_STOCK_SUSPENDED", HttpStatus.CONFLICT),
		Map.entry("ORDER_PRICE_CHANGED", HttpStatus.CONFLICT),
		Map.entry("ORDER_INSUFFICIENT_CASH", HttpStatus.CONFLICT),
		Map.entry("ORDER_INSUFFICIENT_QUANTITY", HttpStatus.CONFLICT),
		Map.entry("ORDER_PRICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE)
	);

	private static List<BaseErrorCode> allCodes() {
		List<BaseErrorCode> codes = new ArrayList<>();
		ALL_ENUMS.forEach(values -> codes.addAll(List.of(values)));
		return codes;
	}

	@Test
	@DisplayName("enum 전체의 code·status 가 apiSpec 11장 목록과 정확히 일치한다 (빠진 것도 남는 것도 없다)")
	void matchesSpecSection11() {
		Map<String, HttpStatus> actual = allCodes().stream()
			.collect(Collectors.toMap(BaseErrorCode::getCode, BaseErrorCode::getStatus));

		assertThat(actual).containsExactlyInAnyOrderEntriesOf(SPEC_SECTION_11);
	}

	@Test
	@DisplayName("code 문자열은 enum 을 통틀어 유일하다")
	void codesAreUniqueAcrossEnums() {
		List<String> codes = allCodes().stream().map(BaseErrorCode::getCode).toList();

		assertThat(codes).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("code 는 enum 이름과 같고 대문자 스네이크다")
	void codeEqualsEnumName() {
		for (BaseErrorCode code : allCodes()) {
			assertThat(code.getCode()).isEqualTo(((Enum<?>) code).name()).matches("[A-Z][A-Z0-9_]*");
		}
	}

	@Test
	@DisplayName("message 는 비어 있지 않다 — 프론트가 그대로 화면에 띄운다 (apiSpec 1.3)")
	void messagesArePresent() {
		assertThat(allCodes()).extracting(BaseErrorCode::getMessage).allMatch(m -> m != null && !m.isBlank());
	}

	@Test
	@DisplayName("도메인 enum 은 자기 도메인 접두사를 쓴다 (backConvention 3장 도메인_원인)")
	void domainPrefixes() {
		assertPrefix(AuthErrorCode.values(), "AUTH_");
		assertPrefix(AiErrorCode.values(), "AI_");
		assertPrefix(DepositErrorCode.values(), "DEPOSIT_");
		assertPrefix(StockErrorCode.values(), "STOCK_");
		assertPrefix(WatchlistErrorCode.values(), "WATCHLIST_");
		assertPrefix(OrderErrorCode.values(), "ORDER_");
	}

	private static void assertPrefix(BaseErrorCode[] values, String prefix) {
		assertThat(Stream.of(values).map(BaseErrorCode::getCode)).allMatch(c -> c.startsWith(prefix));
	}
}
