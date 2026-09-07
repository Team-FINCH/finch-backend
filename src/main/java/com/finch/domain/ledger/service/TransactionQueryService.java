package com.finch.domain.ledger.service;

import com.finch.domain.ledger.dto.request.TransactionFilter;
import com.finch.domain.ledger.dto.response.TransactionRes;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.TransactionQueryRepository;
import com.finch.domain.ledger.repository.TransactionRow;
import com.finch.global.paging.CursorCodec;
import com.finch.global.paging.CursorPage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매매 내역 조회 (apiSpec 8.2, featureSpec 8). 원장을 <b>화면 단위</b>로 읽는다 — 기록은 {@link LedgerService}, 조회는
 * 여기다. 둘을 나눈 이유: 기록 서비스는 {@code MANDATORY} 트랜잭션 안에서 한 줄을 남기는 일만 하고, 조회는 조인·필터·
 * 페이징이라 성격이 다르다. 한 클래스에 두면 "원장을 건드리는 코드"의 범위가 흐려진다.
 * <p>
 * 필터·정렬·페이징의 판정은 전부 SQL 에 있다 ({@link TransactionQueryRepository}). 여기서 하는 것은 커서를 풀고, 한 건 더
 * 읽어 {@code hasNext} 를 정하고, 행을 응답 모양으로 바꾸는 것뿐이다.
 */
@Service
@RequiredArgsConstructor
public class TransactionQueryService {

	private final TransactionQueryRepository transactionQueryRepository;
	private final CursorCodec cursorCodec;

	/**
	 * @param filter {@code ALL} 이면 유형 조건 없이, 아니면 그 원장 유형 하나만. {@code DEPOSIT} 에 출금·초기 지급이 섞이지
	 *               않는 것은 필터 enum 이 원장 유형과 1:1 로 대응해서다 ({@link TransactionFilter}).
	 * @param cursor 이전 응답의 {@code nextCursor}. null 이면 첫 페이지. 깨진 값은 {@code CursorCodec} 이 400 으로 끊는다.
	 * @param size   페이지 크기. 범위 검증은 컨트롤러가 했다 — 여기서는 {@code size + 1} 만 읽는다.
	 */
	@Transactional(readOnly = true)
	public CursorPage<TransactionRes> list(Long userId, TransactionFilter filter, String cursor, int size) {
		// 첫 페이지는 "모든 id 보다 작은 것"이 아니라 "가장 큰 id 부터"다. null 분기를 SQL 에 두지 않으려고 상한을 넘긴다.
		long before = cursor == null ? Long.MAX_VALUE : cursorCodec.decode(cursor);
		int limit = size + 1;

		LedgerType type = filter.ledgerType();
		List<TransactionRow> rows = type == null
			? transactionQueryRepository.findPage(userId, before, limit)
			: transactionQueryRepository.findPageByType(userId, type.name(), before, limit);

		List<TransactionRes> items = rows.stream().map(TransactionRes::from).toList();
		return CursorPage.of(items, size, TransactionRes::transactionId, cursorCodec);
	}
}
