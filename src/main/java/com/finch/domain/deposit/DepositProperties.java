package com.finch.domain.deposit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 충전 정책값 ({@code finch.deposit}). 도메인 패키지에 두는 이유는 {@code AccountProperties} 와 같다 —
 * {@code global} 이 도메인의 설정 모양을 알지 않게 접두사만 공유한다.
 *
 * @param perRequestLimit     1회 충전 한도 (apiSpec 4.2 판정 3). DB 의 {@code ck_payment_amount} 와 같은 값이어야 한다 —
 *                            여기만 올리면 INSERT 가 제약 위반으로 실패한다.
 * @param cumulativeLimit     계정 평생 누적 한도 (apiSpec 4.1). {@code ck_account_deposited} 와 짝이다.
 * @param readyTtl            READY 인 채 버려진 건이 만료되기까지의 시간 (apiSpec 4.2 {@code expiresAt}, 기본 15분).
 * @param publicBaseUrl       <b>우리 서버</b>의 외부 주소. 카카오가 사용자의 브라우저를 되돌려 보낼 승인 콜백 URL 을
 *                            이 값으로 만든다. 카카오페이 콘솔에 이 도메인이 등록돼 있어야 한다.
 * @param frontBaseUrl        <b>프론트</b>의 주소. 결제가 끝난 뒤 사용자를 보낼 화면과 모의 이체 화면이 여기 있다.
 *                            배포는 nginx 가 같은 오리진으로 붙이므로 운영에서는 publicBaseUrl 과 같은 값이다.
 * @param successPath         카카오 승인 성공 시 보낼 프론트 경로. {@code ?paymentId&paymentKey&amount} 가 붙는다 (apiSpec 4.3.1).
 * @param failPath            승인 실패·취소 시 보낼 프론트 경로. {@code ?paymentId&code} 가 붙는다.
 * @param transferCheckoutPath 모의 이체 화면의 프론트 경로. {@code TRANSFER} 의 {@code checkoutUrl} 이 된다 (apiSpec 4.2).
 * @param kakaopay            카카오페이 API 설정.
 */
@ConfigurationProperties("finch.deposit")
public record DepositProperties(
	@DefaultValue("10000000") long perRequestLimit,
	@DefaultValue("100000000") long cumulativeLimit,
	@DefaultValue("15m") Duration readyTtl,
	@DefaultValue("http://localhost:8080") String publicBaseUrl,
	@DefaultValue("http://localhost:5173") String frontBaseUrl,
	@DefaultValue("/deposit/complete") String successPath,
	@DefaultValue("/deposit/fail") String failPath,
	@DefaultValue("/deposit/transfer") String transferCheckoutPath,
	@DefaultValue KakaoPay kakaopay
) {

	/**
	 * 카카오페이 온라인 결제 API (developers.kakaopay.com).
	 *
	 * @param enabled   false 면 {@code KakaoPayGateway} 빈을 만들지 않고 {@code MockTransferGateway} 가 KAKAOPAY 도 맡는다.
	 *                  키가 없는 환경(테스트·CI)용이고 테스트는 항상 이 모드다. 운영은 true 다.
	 * @param secretKey dev/prod Secret Key. 비밀값이라 기본값이 없다 ({@code KAKAOPAY_SECRET_KEY}).
	 * @param cid       가맹점 코드. {@code TC0ONETIME} 은 카카오가 제공하는 테스트 가맹점이라 실제 금전 이동이 없다.
	 * @param baseUrl   API 호스트. 테스트에서 가짜 서버로 바꾼다.
	 * @param timeout   응답 대기 제한. 결제창 URL 을 받는 호출이라 사용자가 기다리고 있다 — 길게 잡을 이유가 없다.
	 */
	public record KakaoPay(
		@DefaultValue("true") boolean enabled,
		String secretKey,
		@DefaultValue("TC0ONETIME") String cid,
		@DefaultValue("https://open-api.kakaopay.com") String baseUrl,
		@DefaultValue("10s") Duration timeout
	) {
	}

	public String successUrl() {
		return frontBaseUrl + successPath;
	}

	public String failUrl() {
		return frontBaseUrl + failPath;
	}

	public String transferCheckoutUrl() {
		return frontBaseUrl + transferCheckoutPath;
	}
}
