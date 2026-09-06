package com.finch.domain.deposit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.domain.deposit.DepositProperties;
import com.finch.domain.deposit.entity.Payment;
import com.finch.domain.deposit.entity.PaymentMethod;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * 카카오 서버를 부르지 않고 응답만 흉내낸다 ({@code KakaoOAuthClientTest} 와 같은 방식).
 * 진짜 카카오는 결제창에서 사람이 QR 을 찍어야 하는 흐름이라 자동 테스트로 반복할 수 없다.
 * <p>
 * 여기서 고정하는 것은 셋이다 — 카카오에 보내는 요청의 모양(인증 헤더·콜백 URL·식별자), 응답에서 무엇을
 * 꺼내 {@code paymentKey} 로 삼는지, 실패가 전부 {@code UNAVAILABLE} 로 모이는지.
 */
class KakaoPayGatewayTest {

	private static final String READY_JSON = """
		{"tid":"T1234567890","next_redirect_pc_url":"https://online-pay.kakao.com/mockup/v1/abc/info",
		"next_redirect_mobile_url":"https://online-pay.kakao.com/mockup/v1/abc/mInfo","created_at":"2026-09-06T10:00:00"}""";

	private static final String APPROVE_JSON = """
		{"aid":"A5678901234","tid":"T1234567890","cid":"TC0ONETIME","partner_order_id":"77",
		"partner_user_id":"finch-account-3","payment_method_type":"MONEY",
		"amount":{"total":1000000,"tax_free":0,"vat":0},"approved_at":"2026-09-06T10:01:00"}""";

	private final List<ClientRequest> sent = new ArrayList<>();

	@Test
	@DisplayName("ready 는 결제창 URL 과 tid 를 돌려준다 — PC 결제창 URL 을 쓴다")
	void readyReturnsCheckoutUrlAndTid() {
		KakaoPayGateway gateway = gatewayOf(request -> json(HttpStatus.OK, READY_JSON));

		PaymentGateway.ReadyResult result = gateway.ready(payment(77L, 3L, 1_000_000L));

		assertThat(result.checkoutUrl()).isEqualTo("https://online-pay.kakao.com/mockup/v1/abc/info");
		assertThat(result.pgTid()).isEqualTo("T1234567890");
	}

	/**
	 * 요청의 모양이 카카오 콘솔 설정·approve 요청과 맞물린다. 인증 헤더 형식이 틀리면 401, 승인 콜백 도메인이
	 * 콘솔에 등록된 것과 다르면 결제창이 뜨지 않는다. 둘 다 운영에서만 드러나는 실패라 여기서 못 박는다.
	 */
	@Test
	@DisplayName("ready 요청은 SECRET_KEY 헤더 · 테스트 CID · 우리 서버의 승인 콜백 URL 을 싣는다")
	void readySendsContractedRequest() {
		KakaoPayGateway gateway = gatewayOf(request -> json(HttpStatus.OK, READY_JSON));

		gateway.ready(payment(77L, 3L, 1_000_000L));

		ClientRequest request = sent.getFirst();
		assertThat(request.url().toString()).isEqualTo("https://kakao.test/online/v1/payment/ready");
		assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("SECRET_KEY test-secret");
		assertThat(request.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
	}

	@Test
	@DisplayName("approve 는 승인 번호(aid)를 paymentKey 로, amount.total 을 승인 금액으로 돌려준다")
	void approveReturnsAidAsPaymentKey() {
		KakaoPayGateway gateway = gatewayOf(request -> json(HttpStatus.OK, APPROVE_JSON));
		Payment payment = payment(77L, 3L, 1_000_000L);
		payment.attachPgTid("T1234567890");

		PaymentGateway.ApproveResult result = gateway.approve(payment, "pg-token");

		assertThat(result.paymentKey()).isEqualTo("A5678901234");
		assertThat(result.approvedAmount()).isEqualTo(1_000_000L);
		assertThat(sent.getFirst().url().toString()).isEqualTo("https://kakao.test/online/v1/payment/approve");
	}

	/** 카카오 오류 본문의 {@code error_code} 가 {@code fail_code} 에 남아야 운영에서 원인을 가를 수 있다. */
	@Test
	@DisplayName("카카오가 4xx 를 주면 UNAVAILABLE 이고 fail_code 는 KAKAOPAY_{error_code} 다")
	void kakaoErrorBecomesUnavailable() {
		KakaoPayGateway gateway = gatewayOf(request -> json(HttpStatus.BAD_REQUEST, """
			{"error_code":-780,"error_message":"approval failure!","extras":{"method_result_code":"USER_LOCKED"}}"""));

		assertThatThrownBy(() -> gateway.ready(payment(77L, 3L, 1_000_000L)))
			.isInstanceOf(PaymentGatewayException.class)
			.satisfies(e -> {
				PaymentGatewayException ex = (PaymentGatewayException) e;
				assertThat(ex.getKind()).isEqualTo(PaymentGatewayException.Kind.UNAVAILABLE);
				assertThat(ex.getFailCode()).isEqualTo("KAKAOPAY_-780");
			});
	}

	@Test
	@DisplayName("응답이 200 이어도 tid 가 없으면 UNAVAILABLE 이다 — 결제창 없이 진행할 수 없다")
	void emptyBodyBecomesUnavailable() {
		KakaoPayGateway gateway = gatewayOf(request -> json(HttpStatus.OK, "{}"));

		assertThatThrownBy(() -> gateway.ready(payment(77L, 3L, 1_000_000L)))
			.isInstanceOf(PaymentGatewayException.class)
			.extracting("failCode").isEqualTo("KAKAOPAY_EMPTY_RESPONSE");
	}

	@Test
	@DisplayName("연결 자체가 안 되면 UNAVAILABLE KAKAOPAY_UNREACHABLE 이다")
	void connectionFailureBecomesUnavailable() {
		KakaoPayGateway gateway = gatewayOf(request -> {
			throw new WebClientRequestException(new java.net.ConnectException("refused"),
				request.method(), request.url(), request.headers());
		});

		assertThatThrownBy(() -> gateway.ready(payment(77L, 3L, 1_000_000L)))
			.isInstanceOf(PaymentGatewayException.class)
			.extracting("failCode").isEqualTo("KAKAOPAY_UNREACHABLE");
	}

	private KakaoPayGateway gatewayOf(Function<ClientRequest, ClientResponse> responder) {
		WebClient.Builder builder = WebClient.builder()
			.exchangeFunction(request -> {
				sent.add(request);
				return Mono.just(responder.apply(request));
			});
		return new KakaoPayGateway(builder, properties());
	}

	private static DepositProperties properties() {
		return new DepositProperties(10_000_000L, 100_000_000L, Duration.ofMinutes(15),
			"http://localhost:8080", "http://localhost:5173", "/deposit/complete", "/deposit/fail", "/deposit/transfer",
			new DepositProperties.KakaoPay(true, "test-secret", "TC0ONETIME", "https://kakao.test",
				Duration.ofSeconds(5)));
	}

	private static Payment payment(long id, long accountId, long amount) {
		Payment payment = Payment.ready(accountId, PaymentMethod.KAKAOPAY, amount, Instant.now().plusSeconds(900));
		ReflectionTestUtils.setField(payment, "id", id);
		return payment;
	}

	private static ClientResponse json(HttpStatus status, String body) {
		return ClientResponse.create(status)
			.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.body(body)
			.build();
	}
}
