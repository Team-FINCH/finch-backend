package com.finch.domain.order.service;

import com.finch.domain.account.service.AccountService;
import com.finch.domain.order.dto.response.LastBuyRes;
import com.finch.domain.order.dto.response.TradeSummaryRes;
import com.finch.domain.order.repository.TradeRepository;
import com.finch.global.paging.CursorCodec;
import com.finch.global.paging.CursorPage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체결 이력 조회. 체결은 {@code OrderService} 가 쓰고 여기는 읽기만 한다 — 원장의 {@code LedgerService}/{@code TransactionQueryService}
 * 와 같은 분리다. 첫 호출자는 AI 서버 내부 API(apiSpec 9.2)이고, ai(5층) → order(4층) 은 위에서 아래라 그대로 부를 수 있다.
 * <p>
 * 화면용 매매 내역({@code GET /transactions})은 여기가 아니라 원장 프로젝션이다 — 그쪽은 충전·출금이 섞인 원장 단위이고
 * 이쪽은 체결만이다.
 */
@Service
@RequiredArgsConstructor
public class TradeQueryService {

	private final TradeRepository tradeRepository;
	private final AccountService accountService;
	private final CursorCodec cursorCodec;

	/**
	 * 사용자의 체결을 최신순으로 한 페이지. 계좌는 토큰(여기서는 {@code X-User-Id})의 사용자로 찾는다 (apiSpec 1.6).
	 *
	 * @param cursor 이전 응답의 {@code nextCursor}. null 이면 첫 페이지. 깨진 값은 {@code CursorCodec} 이 400 으로 끊는다.
	 * @param size   페이지 크기. 내부 API 는 기본·최대 100 ({@code PageSize.forInternal}) — 검증은 호출자가 했다.
	 */
	@Transactional(readOnly = true)
	public CursorPage<TradeSummaryRes> listForInternal(Long userId, String cursor, int size) {
		Long accountId = accountService.getBalance(userId).accountId();
		long before = cursor == null ? Long.MAX_VALUE : cursorCodec.decode(cursor);
		List<TradeSummaryRes> rows = tradeRepository
			.findByAccountIdAndIdLessThanOrderByIdDesc(accountId, before, PageRequest.of(0, size + 1))
			.stream().map(TradeSummaryRes::from).toList();
		return CursorPage.of(rows, size, TradeSummaryRes::tradeId, cursorCodec);
	}

	/**
	 * 종목별 마지막 매수 (알림함 apiSpec 6.4). 알림함의 "왜 담으셨나요?" 가 어느 매수에서 나온 질문인지 정한다 — 같은 종목을 다시 사면
	 * 이 값이 바뀌어 새 항목이 된다. 사용자가 매수한 적 있는 종목 전부가 담기고, 보유 중인지는 보지 않는다(호출자가 거른다).
	 */
	@Transactional(readOnly = true)
	public List<LastBuyRes> lastBuys(Long userId) {
		Long accountId = accountService.getBalance(userId).accountId();
		return tradeRepository.findLastBuys(accountId).stream().map(LastBuyRes::from).toList();
	}
}
