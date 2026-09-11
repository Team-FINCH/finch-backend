package com.finch.domain.inbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.ai.service.WikiThesisService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.service.DepositService;
import com.finch.domain.inbox.dto.response.InboxRes;
import com.finch.domain.order.dto.request.OrderReq;
import com.finch.domain.order.dto.response.OrderRes;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.service.OrderService;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.global.util.MarketClock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 알림함 "적어야 할 것" 이 보유·체결·논지·읽음을 어떻게 엮는지 실제 DB 로 본다 (apiSpec 6.4). 매수는 진짜 주문 경로로 만든다 —
 * 체결 id·시각과 보유 행이 그 경로에서 나오기 때문이다 ({@code OrderServiceTest} 와 같은 준비).
 * <p>
 * <b>목은 둘이다.</b> {@code MarketClock}(테스트 시각과 무관하게 장중)과 {@code WikiThesisService}(AI 서버 없이 논지 목록을 정한다).
 * 위키 캐시·중계 자체는 {@code WikiThesisServiceTest} 가 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InboxServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(950_000_000L);
	private static final String SAMSUNG = "005930";
	private static final String HYNIX = "000660";

	@Autowired
	private InboxService inboxService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private DepositService depositService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PriceCache priceCache;

	@MockitoBean
	private MarketClock marketClock;

	@MockitoBean
	private WikiThesisService wikiThesisService;

	@BeforeEach
	void setUp() {
		given(marketClock.isOpen()).willReturn(true);
		given(wikiThesisService.activeThesisTickers(anyLong())).willReturn(Optional.of(Set.of()));
		price(SAMSUNG, 70_000);
		price(HYNIX, 200_000);
	}

	@Test
	@DisplayName("논지 없는 보유 종목마다 record 하나 — id·tradeId 는 마지막 매수, 최신 매수가 위, 처음엔 전부 안 읽음")
	void recordPerHeldStockWithoutThesis() {
		Long userId = fundedUser();
		OrderRes samsung = orderService.place(userId, buy(SAMSUNG, 1));
		OrderRes hynix = orderService.place(userId, buy(HYNIX, 1));

		InboxRes inbox = inboxService.list(userId);

		assertThat(inbox.unreadCount()).isEqualTo(2);
		assertThat(inbox.items()).extracting(InboxRes.Item::stockCode).containsExactly(HYNIX, SAMSUNG);
		InboxRes.Item first = inbox.items().getFirst();
		assertThat(first.itemId()).isEqualTo("record-" + HYNIX + "-" + hynix.orderId());
		assertThat(first.kind()).isEqualTo(InboxRes.Kind.RECORD);
		assertThat(first.tradeId()).isEqualTo(hynix.orderId());
		assertThat(first.title()).isEqualTo("SK하이닉스, 왜 담으셨나요?");
		assertThat(first.stockName()).isEqualTo("SK하이닉스");
		assertThat(first.unread()).isTrue();
		// 체결 시각이다. DB(마이크로초)와 응답 객체(메모리 값)의 정밀도가 달라 1ms 안에서 본다.
		assertThat(first.createdAt().toInstant()).isCloseTo(hynix.executedAt().toInstant(), within(1, ChronoUnit.MILLIS));
		assertThat(first.createdAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		assertThat(inbox.items().get(1).tradeId()).isEqualTo(samsung.orderId());
	}

	@Test
	@DisplayName("active 논지가 있는 종목은 빠진다 — 처리 완료를 따로 표시하지 않는다")
	void thesisRemovesItem() {
		Long userId = fundedUser();
		orderService.place(userId, buy(SAMSUNG, 1));
		orderService.place(userId, buy(HYNIX, 1));
		given(wikiThesisService.activeThesisTickers(userId)).willReturn(Optional.of(Set.of(HYNIX)));

		InboxRes inbox = inboxService.list(userId);

		assertThat(inbox.items()).extracting(InboxRes.Item::stockCode).containsExactly(SAMSUNG);
		assertThat(inbox.unreadCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("읽음 표시는 뱃지만 끄고 항목은 남긴다. 두 번 불러도, 모르는 id 여도 조용히 끝난다")
	void markReadIsIdempotent() {
		Long userId = fundedUser();
		orderService.place(userId, buy(SAMSUNG, 1));
		String itemId = inboxService.list(userId).items().getFirst().itemId();

		inboxService.markRead(userId, itemId);
		inboxService.markRead(userId, itemId);
		inboxService.markRead(userId, "record-999999-1");

		InboxRes inbox = inboxService.list(userId);
		assertThat(inbox.unreadCount()).isZero();
		assertThat(inbox.items()).singleElement().satisfies(item -> {
			assertThat(item.itemId()).isEqualTo(itemId);
			assertThat(item.unread()).isFalse();
		});
	}

	/** 다시 산 것은 새 사건이다. 예전 매수에서 나온 질문을 읽었다고 새 매수의 질문까지 읽은 것으로 두지 않는다. */
	@Test
	@DisplayName("같은 종목을 다시 사면 id 가 바뀌어 다시 안 읽음이 된다")
	void rebuyMakesNewUnreadItem() {
		Long userId = fundedUser();
		orderService.place(userId, buy(SAMSUNG, 1));
		String firstId = inboxService.list(userId).items().getFirst().itemId();
		inboxService.markRead(userId, firstId);

		OrderRes again = orderService.place(userId, buy(SAMSUNG, 1));

		InboxRes inbox = inboxService.list(userId);
		assertThat(inbox.items()).singleElement().satisfies(item -> {
			assertThat(item.itemId()).isEqualTo("record-" + SAMSUNG + "-" + again.orderId()).isNotEqualTo(firstId);
			assertThat(item.unread()).isTrue();
		});
	}

	@Test
	@DisplayName("전량 매도하면 빠진다 — 판정은 보유 수량 > 0 이다")
	void sellAllRemovesItem() {
		Long userId = fundedUser();
		orderService.place(userId, buy(SAMSUNG, 2));
		orderService.place(userId, sell(SAMSUNG, 2));

		assertThat(inboxService.list(userId).items()).isEmpty();
	}

	/** 논지 유무를 모르는 채로 전부 내면 이미 이유를 적은 종목에 다시 묻게 된다. 잠시 비는 편이 낫다. */
	@Test
	@DisplayName("위키를 모르면(empty) record 를 하나도 내지 않고 에러도 내지 않는다")
	void unknownWikiYieldsEmpty() {
		Long userId = fundedUser();
		orderService.place(userId, buy(SAMSUNG, 1));
		given(wikiThesisService.activeThesisTickers(userId)).willReturn(Optional.empty());

		InboxRes inbox = inboxService.list(userId);

		assertThat(inbox.items()).isEmpty();
		assertThat(inbox.unreadCount()).isZero();
	}

	@Test
	@DisplayName("보유가 없으면 AI 위키를 부르지 않고 빈 목록이다")
	void noHoldingsSkipsWiki() {
		Long userId = fundedUser();

		assertThat(inboxService.list(userId)).isEqualTo(InboxRes.empty());
		verify(wikiThesisService, never()).activeThesisTickers(userId);
	}

	/** 계좌를 열고 충전으로 예수금을 만든다 ({@code OrderServiceTest.fundedUser} 와 같다). */
	private Long fundedUser() {
		User user = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		long amount = 1_000_000L;
		Long paymentId = depositService.ready(user.getId(), PaymentMethod.TRANSFER, amount).paymentId();
		MockApproveRes approved = depositService.mockApprove(user.getId(), paymentId, MockScenario.SUCCESS);
		depositService.confirm(user.getId(), approved.paymentId(), approved.paymentKey(), amount);
		return user.getId();
	}

	private void price(String stockCode, long currentPrice) {
		priceCache.put(stockCode, new PriceEntry(currentPrice, currentPrice, Instant.now()));
	}

	private static OrderReq buy(String stockCode, long quantity) {
		return new OrderReq(stockCode, OrderSide.BUY, quantity);
	}

	private static OrderReq sell(String stockCode, long quantity) {
		return new OrderReq(stockCode, OrderSide.SELL, quantity);
	}
}
