package com.finch.domain.deposit.gateway;

import com.finch.domain.deposit.DepositProperties;
import com.finch.domain.deposit.entity.Payment;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Exceptions;

/**
 * 카카오페이 온라인 결제 API (단건). {@code toss-mock-pay} 프로토타입의 {@code KakaoPayClient} 를 옮긴 것이다.
 * <ul>
 *   <li>{@code ready} — 결제 준비. {@code tid} 와 결제창 URL 을 받는다. 사용자는 결제창(QR)에서 휴대폰 인증을 한다.</li>
 *   <li>{@code approve} — 인증을 마친 사용자가 {@code approval_url?pg_token=} 으로 돌아오면 그 토큰으로 최종 승인.</li>
 * </ul>
 * 인증 헤더는 {@code Authorization: SECRET_KEY {key}} 다 (구 어드민 키 방식이 아니다).
 * <p>
 * <b>{@code enabled=false} 면 이 빈이 없다.</b> 키가 없는 환경(테스트·CI)에서 {@code KAKAOPAY} 는
 * {@link MockTransferGateway} 가 맡는다. 실제 카카오를 치는 자동 테스트는 없다 — 결제창에서 사람이 QR 을
 * 찍어야 하는 흐름이라 반복할 수 없고, 응답만 흉내내는 {@code KakaoPayGatewayTest} 가 대신한다.
 * <p>
 * <b>모든 실패는 {@code UNAVAILABLE} 이다.</b> 카카오 오류 코드는 수십 종이고 어느 것이 "사용자 잘못"인지
 * 문서만으로 가를 수 없다. 코드는 {@code KAKAOPAY_{error_code}} 로 {@code fail_code} 와 로그에 남기고,
 * 사용자에게는 "결제를 시작할 수 없습니다" 하나로 답한다 (featureSpec 3.3).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "finch.deposit.kakaopay", name = "enabled", havingValue = "true")
public class KakaoPayGateway implements PaymentGateway {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
	};

	private final WebClient webClient;
	private final DepositProperties properties;
	private final Duration timeout;

	/**
	 * S0 의 공통 빌더로 클라이언트를 만든다 ({@code WebClientConfig}). base URL·인증 헤더·대기 시간은 이 도메인의
	 * 값이고 빌더는 연결 제한과 요청 추적 헤더만 준다.
	 */
	public KakaoPayGateway(WebClient.Builder builder, DepositProperties properties) {
		this.properties = properties;
		this.timeout = properties.kakaopay().timeout();
		this.webClient = builder
			.baseUrl(properties.kakaopay().baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "SECRET_KEY " + properties.kakaopay().secretKey())
			.build();
	}

	/**
	 * {@code partner_order_id} 는 우리 {@code paymentId}, {@code partner_user_id} 는 계좌 id 다.
	 * 카카오는 둘을 approve 에서 <b>같은 값으로</b> 다시 요구한다 — 둘 다 payment 행에서 나오므로 따로 저장하지 않는다.
	 * <p>
	 * 콜백 URL 세 개 중 서버로 오는 것은 {@code approval_url} 하나다 (apiSpec 4.3.1). 취소·실패는 카카오가
	 * 사용자의 브라우저를 <b>프론트 실패 화면으로 바로</b> 보낸다 — 서버가 받아도 할 일이 "실패로 표시"뿐이고,
	 * 그 건은 READY 로 남아 만료 배치가 정리한다. 취소 콜백을 서버로 받는 것은 후속 과제다.
	 */
	@Override
	public ReadyResult ready(Payment payment) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("cid", properties.kakaopay().cid());
		body.put("partner_order_id", String.valueOf(payment.getId()));
		body.put("partner_user_id", partnerUserId(payment));
		body.put("item_name", "예수금 충전");
		body.put("quantity", 1);
		body.put("total_amount", payment.getAmount());
		body.put("tax_free_amount", 0);
		body.put("approval_url", approvalUrl(payment));
		body.put("cancel_url", failUrl(payment, "DEPOSIT_PAYMENT_FAILED"));
		body.put("fail_url", failUrl(payment, "DEPOSIT_PAYMENT_FAILED"));

		Map<String, Object> res = post("/online/v1/payment/ready", body, payment);
		String tid = string(res, "tid");
		// PC 결제창(QR)이다. 모바일 브라우저 전용 URL(next_redirect_mobile_url)은 데스크톱에서 열리지 않는다.
		String checkoutUrl = string(res, "next_redirect_pc_url");
		if (tid == null || checkoutUrl == null) {
			throw unavailable("KAKAOPAY_EMPTY_RESPONSE", "카카오페이 ready 응답에 tid·결제창 URL 이 없다", payment);
		}
		return new ReadyResult(checkoutUrl, tid);
	}

	/** 승인 응답의 {@code aid}(승인 번호)가 {@code paymentKey} 다. {@code tid} 는 준비 번호라 승인 전에도 있다. */
	@Override
	public ApproveResult approve(Payment payment, String pgToken) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("cid", properties.kakaopay().cid());
		body.put("tid", payment.getPgTid());
		body.put("partner_order_id", String.valueOf(payment.getId()));
		body.put("partner_user_id", partnerUserId(payment));
		body.put("pg_token", pgToken);

		Map<String, Object> res = post("/online/v1/payment/approve", body, payment);
		String aid = string(res, "aid");
		if (aid == null) {
			throw unavailable("KAKAOPAY_EMPTY_RESPONSE", "카카오페이 approve 응답에 aid 가 없다", payment);
		}
		return new ApproveResult(aid, approvedTotal(res));
	}

	private Map<String, Object> post(String path, Map<String, Object> body, Payment payment) {
		Reply reply;
		try {
			reply = webClient.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				// 4xx·5xx 를 예외로 바꾸지 않고 상태와 본문을 같이 받는다 — 카카오 오류 본문의 error_code 가 필요하다.
				.exchangeToMono(response -> response.bodyToMono(MAP)
					.defaultIfEmpty(Map.of())
					.map(b -> new Reply(response.statusCode(), b)))
				.timeout(timeout)
				.block();
		} catch (WebClientRequestException e) {
			throw unavailable("KAKAOPAY_UNREACHABLE", "카카오페이 서버에 연결할 수 없다: " + e.getMessage(), payment);
		} catch (RuntimeException e) {
			if (Exceptions.unwrap(e) instanceof TimeoutException) {
				throw unavailable("KAKAOPAY_TIMEOUT", "카카오페이 응답이 " + timeout + " 안에 오지 않았다", payment);
			}
			throw unavailable("KAKAOPAY_CLIENT_ERROR", "카카오페이 호출 실패: " + e.getMessage(), payment);
		}
		if (reply == null) {
			throw unavailable("KAKAOPAY_EMPTY_RESPONSE", "카카오페이 응답이 비어 있다", payment);
		}
		if (reply.status().isError()) {
			// 오류 본문: {error_code, error_message, extras:{method_result_code, method_result_message}}
			String code = "KAKAOPAY_" + reply.body().getOrDefault("error_code", "ERROR");
			throw unavailable(code, "카카오페이 " + reply.status() + " " + reply.body(), payment);
		}
		return reply.body();
	}

	private PaymentGatewayException unavailable(String failCode, String message, Payment payment) {
		// 사용자에게는 사유를 알리지 않지만 서버는 알아야 한다 — 이 로그가 없으면 키 오류·도메인 미등록·한도가 전부 같은 502 다.
		log.warn("카카오페이 호출 실패 paymentId={} code={} {}", payment.getId(), failCode, message);
		return new PaymentGatewayException(PaymentGatewayException.Kind.UNAVAILABLE, failCode, message);
	}

	private String approvalUrl(Payment payment) {
		return UriComponentsBuilder.fromUriString(properties.publicBaseUrl())
			.path("/api/v1/deposits/kakao/approval")
			.queryParam("paymentId", payment.getId())
			.build()
			.toUriString();
	}

	private String failUrl(Payment payment, String code) {
		return UriComponentsBuilder.fromUriString(properties.failUrl())
			.queryParam("paymentId", payment.getId())
			.queryParam("code", code)
			.build()
			.toUriString();
	}

	private static String partnerUserId(Payment payment) {
		return "finch-account-" + payment.getAccountId();
	}

	private static String string(Map<String, Object> map, String key) {
		Object value = map.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private static long approvedTotal(Map<String, Object> res) {
		if (res.get("amount") instanceof Map<?, ?> amount && amount.get("total") instanceof Number total) {
			return total.longValue();
		}
		return -1L;
	}

	private record Reply(HttpStatusCode status, Map<String, Object> body) {
	}
}
