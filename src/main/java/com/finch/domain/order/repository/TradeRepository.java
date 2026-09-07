package com.finch.domain.order.repository;

import com.finch.domain.order.entity.Trade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** `trade` 는 order 도메인 소유다. 다른 도메인은 이 리포지토리를 import 하지 않는다 (backConvention 2.4 규칙 3). */
public interface TradeRepository extends JpaRepository<Trade, Long> {

	/**
	 * 계좌의 체결을 최신순으로. 불변식 3·6 대조와 <b>S11 내부 API({@code GET /internal/v1/trades})의 커서 조회</b>가 쓴다 —
	 * {@code ix_trade_account_id_desc} 가 이 순서 그대로다. 화면용 매매 내역은 여기가 아니라 S4 의 원장 프로젝션이다.
	 */
	List<Trade> findByAccountIdOrderByIdDesc(Long accountId);
}
