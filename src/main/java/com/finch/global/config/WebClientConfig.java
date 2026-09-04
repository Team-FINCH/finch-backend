package com.finch.global.config;

import com.finch.global.filter.RequestIdFilter;
import io.netty.channel.ChannelOption;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 외부 API 호출(카카오페이·KIS·AI)의 <b>공통 뼈대만</b> 만든다. 완성된 {@code WebClient} 를 여기서
 * 만들지 않는 이유 — base URL·인증 헤더·응답 대기 시간이 셋 다 다르다. 하나로 합치면 KIS 를 고칠 때
 * AI 호출이 함께 바뀐다. 각 도메인이 이 빌더를 받아 자기 클라이언트 빈을 만든다 (backConvention 8장).
 * <p>
 * <b>4xx·5xx 를 예외로 바꾸지 않는다.</b> 상태 코드별 판단이 도메인마다 다르기 때문이다 — KIS 의 429 는
 * {@code Retry-After} 를 보고 기다릴 신호이고(S10), AI 의 429 는 {@code AI_UPSTREAM_RATE_LIMITED} 로
 * 재포장할 신호이며(apiSpec 10.4), 카카오의 4xx 는 결제 실패 사유다. 공통 자리에서 예외로 바꿔 버리면
 * 그 구분이 스택 트레이스 뒤로 숨는다. 그래서 여기에 {@code defaultStatusHandler} 를 두지 않는다.
 */
@Configuration
public class WebClientConfig {

	/**
	 * <b>프로토타입 스코프다.</b> {@code WebClient.Builder} 는 가변 객체라 도메인이 base URL 을 얹는
	 * 순간 그 인스턴스가 바뀐다. 싱글턴으로 두면 먼저 초기화된 도메인의 설정이 다음 도메인에 새어
	 * 들어가고, 그 증상은 <b>순서에 따라 달라져</b> 재현이 어렵다. Boot 가 자동 구성하는 빌더도 같은
	 * 이유로 프로토타입이다.
	 */
	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	WebClient.Builder webClientBuilder(FinchProperties properties) {
		HttpClient httpClient = HttpClient.create()
			// 연결 실패는 상대가 누구든 빨리 포기하는 것이 맞다. 응답 대기 시간은 도메인이 정한다.
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.http().connectTimeout().toMillis());

		return WebClient.builder()
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.filter(propagateRequestId());
	}

	/**
	 * 우리 요청 식별자를 외부 호출에도 싣는다 (apiSpec 1.1 요청 추적).
	 * <p>
	 * 이것이 없으면 "사용자 요청 하나"의 로그가 우리 서버에서 끊긴다. AI 서버 로그와 우리 로그를
	 * 같은 값으로 묶을 수 있어야 느린 응답의 원인이 어느 쪽인지 가른다.
	 * <p>
	 * MDC 값은 {@link RequestIdFilter} 가 요청 스레드에 심은 것이다. 이 필터 함수는 구독 시점에
	 * 도는데, 우리 호출은 전부 서블릿 스레드에서 {@code block()} 으로 기다리므로 같은 스레드다.
	 * 나중에 응답을 다른 스케줄러로 넘기게 되면 이 전제가 깨지므로 그때 컨텍스트 전파를 다시 봐야 한다.
	 */
	private static ExchangeFilterFunction propagateRequestId() {
		return (request, next) -> {
			String requestId = MDC.get(RequestIdFilter.MDC_KEY);
			if (requestId == null) {
				return next.exchange(request);
			}
			return next.exchange(ClientRequest.from(request)
				.header(RequestIdFilter.HEADER, requestId)
				.build());
		};
	}
}
