package com.finch.domain.inbox.service;

import com.finch.domain.ai.service.WikiThesisService;
import com.finch.domain.inbox.dto.response.InboxRes;
import com.finch.domain.inbox.repository.InboxReadRepository;
import com.finch.domain.order.dto.response.LastBuyRes;
import com.finch.domain.order.service.TradeQueryService;
import com.finch.domain.portfolio.dto.response.HeldStockRes;
import com.finch.domain.portfolio.service.PortfolioQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림함 (apiSpec 6.4, GitLab 이슈 #57). inbox 는 portfolio(3층)·order(4층)·ai(5층)를 읽으므로 <b>6층</b>이다 (backConvention 2.4).
 * <p>
 * <b>항목을 저장하지 않고 조회 때마다 계산한다.</b> "적어야 할 것"({@code record}) = 보유 종목 중 위키에 {@code active} 논지가 없는 것.
 * 저장해 두는 방식(체결 이벤트로 행을 쌓고 처리되면 닫기)을 기각한 이유 — 논지는 알림함 시트·위키 탭·AI 채팅 세 곳에서 생기는데 채팅
 * 쪽은 백엔드를 지나지 않아 "처리됨" 을 알 방법이 없다. 계산하면 어느 경로로 적었든 다음 조회에서 빠진다. 이미 보유 중이던 종목도
 * 처음부터 목록에 나오고, 체결 쪽에 배선이 필요 없다.
 * <p>
 * <b>항목 id 는 종목의 마지막 매수를 가리킨다</b> ({@code record-{종목코드}-{tradeId}}). 같은 종목을 다시 사면 id 가 바뀌어 새 항목이
 * 되고 다시 안 읽음으로 뜬다 — "새로 담았다" 는 사건이 다시 생긴 것이기 때문이다.
 * <p>
 * {@link #list} 에 트랜잭션을 걸지 않는다. 가운데 AI 위키 호출(HTTP)이 끼어 있어 DB 커넥션을 그 동안 잡아 둘 이유가 없다 — 앞뒤 조회는
 * 각자의 읽기 트랜잭션이다. 세 조회 사이에 체결이 끼면 한 번 어긋난 목록이 나갈 수 있지만, 다음 조회에서 맞춰진다.
 */
@Service
public class InboxService {

	static final String RECORD_PREFIX = "record-";

	private final PortfolioQueryService portfolioQueryService;
	private final TradeQueryService tradeQueryService;
	private final WikiThesisService wikiThesisService;
	private final InboxReadRepository inboxReadRepository;
	private final Clock clock;

	@Autowired
	public InboxService(PortfolioQueryService portfolioQueryService, TradeQueryService tradeQueryService,
		WikiThesisService wikiThesisService, InboxReadRepository inboxReadRepository) {
		this(portfolioQueryService, tradeQueryService, wikiThesisService, inboxReadRepository, Clock.systemUTC());
	}

	public InboxService(PortfolioQueryService portfolioQueryService, TradeQueryService tradeQueryService,
		WikiThesisService wikiThesisService, InboxReadRepository inboxReadRepository, Clock clock) {
		this.portfolioQueryService = portfolioQueryService;
		this.tradeQueryService = tradeQueryService;
		this.wikiThesisService = wikiThesisService;
		this.inboxReadRepository = inboxReadRepository;
		this.clock = clock;
	}

	/**
	 * 목록. 최신 매수가 위다.
	 * <p>
	 * <b>위키를 읽지 못하면 {@code record} 를 하나도 내지 않는다</b> ({@link WikiThesisService} 가 옛 값도 없이 empty 를 준 경우).
	 * 논지가 있는지 모르는 채로 전부 내면 이미 이유를 적은 종목에 "왜 담으셨나요?" 가 다시 뜬다 — 잠시 비는 편이 낫다. 에러도 내지
	 * 않는다 — 알림함이 실패하면 세 화면 헤더의 뱃지가 같이 실패한다.
	 */
	public InboxRes list(long userId) {
		List<HeldStockRes> held = portfolioQueryService.heldStocks(userId);
		if (held.isEmpty()) {
			return InboxRes.empty();
		}
		Optional<Set<String>> theses = wikiThesisService.activeThesisTickers(userId);
		if (theses.isEmpty()) {
			return InboxRes.empty();
		}
		Map<String, LastBuyRes> lastBuys = tradeQueryService.lastBuys(userId).stream()
			.collect(Collectors.toMap(LastBuyRes::stockCode, Function.identity()));

		List<Pending> pending = new ArrayList<>();
		for (HeldStockRes stock : held) {
			LastBuyRes lastBuy = lastBuys.get(stock.stockCode());
			// 매수 체결 없이 생긴 보유는 정상 경로에 없다. "어느 매수에서 나온 질문인가" 를 댈 수 없으니 항목을 만들지 않는다.
			if (theses.get().contains(stock.stockCode()) || lastBuy == null) {
				continue;
			}
			pending.add(new Pending(RECORD_PREFIX + stock.stockCode() + "-" + lastBuy.tradeId(), stock, lastBuy));
		}
		if (pending.isEmpty()) {
			return InboxRes.empty();
		}
		Set<String> read = new HashSet<>(
			inboxReadRepository.findReadItemIds(userId, pending.stream().map(Pending::itemId).toList()));

		List<InboxRes.Item> items = pending.stream()
			.sorted(Comparator.comparing((Pending p) -> p.lastBuy().executedAt()).reversed()
				.thenComparing(Pending::itemId))
			.map(p -> InboxRes.Item.record(p.itemId(), p.stock().stockCode(), p.stock().stockName(),
				p.lastBuy().tradeId(), p.lastBuy().executedAt(), !read.contains(p.itemId())))
			.toList();
		return InboxRes.of(items);
	}

	/**
	 * 읽음 표시. 멱등이다 — 이미 읽었거나 목록에 없는 id 도 그대로 끝난다 (apiSpec 6.4). 목록에 있는지 확인하지 않는 이유 — 확인하려면
	 * {@link #list} 를 다시 계산해야 하고(AI 호출 포함), 지난 id 에 읽음 행이 하나 남는 것은 무해하다.
	 */
	@Transactional
	public void markRead(long userId, String itemId) {
		inboxReadRepository.markRead(userId, itemId, Instant.now(clock));
	}

	private record Pending(String itemId, HeldStockRes stock, LastBuyRes lastBuy) {
	}
}
