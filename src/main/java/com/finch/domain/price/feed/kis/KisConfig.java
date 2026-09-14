package com.finch.domain.price.feed.kis;

import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.feed.RealtimeCoverage;
import com.finch.global.lock.LeaderLock;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * KIS 빈들. <b>{@code finch.price.provider=kis} 일 때만</b> 만들어진다 — fake 면 키 풀도 토큰 관리자도 클라이언트도 없다.
 * 테스트·로컬은 fake 라 이 설정이 통째로 빠지고, KIS 코드는 {@code KisClientTest} 처럼 직접 조립해서 본다.
 * <p>
 * {@code WebClient.Builder} 는 프로토타입이라 토큰 관리자와 클라이언트가 각자 새 빌더를 받는다 ({@code WebClientConfig} 주석).
 * <p>
 * 실시간 티어({@link KisRealtimeFeed})는 그 위에 <b>{@code finch.kis.realtime.enabled=true}</b> 가 더 필요하다. 꺼져 있으면
 * {@link RealtimeCoverage#NONE} 이 그 자리에 들어가 폴링이 전부 맡는다 — 폴링 코드는 실시간이 있는지 모른다.
 */
@Configuration
@ConditionalOnProperty(name = "finch.price.provider", havingValue = "kis")
public class KisConfig {

	@Bean
	KisKeyPool kisKeyPool(KisProperties properties) {
		return new KisKeyPool(properties.keys());
	}

	@Bean
	KisTokenManager kisTokenManager(ObjectProvider<WebClient.Builder> builder, StringRedisTemplate redisTemplate,
		KisProperties properties) {
		return new KisTokenManager(builder.getObject(), redisTemplate, properties);
	}

	@Bean
	KisClient kisClient(ObjectProvider<WebClient.Builder> builder, KisTokenManager tokenManager, KisProperties properties,
		MeterRegistry meterRegistry) {
		return new KisClient(builder.getObject(), tokenManager, properties, meterRegistry);
	}

	@Bean
	@ConditionalOnProperty(name = "finch.kis.realtime.enabled", havingValue = "true")
	KisApprovalKeyClient kisApprovalKeyClient(ObjectProvider<WebClient.Builder> builder, KisProperties properties) {
		return new KisApprovalKeyClient(builder.getObject(), properties);
	}

	/**
	 * 표준(JSR-356) 클라이언트. 서블릿 컨테이너(Tomcat)의 웹소켓 구현을 그대로 쓴다 — 의존성 추가 없음.
	 * <p>
	 * 텍스트 버퍼를 넉넉히 잡는다. Tomcat 기본은 8KB 인데, 체결이 몰리면 KIS 가 한 프레임에 여러 건을 이어 붙여 보내고
	 * 버퍼를 넘기는 프레임은 세션을 끊는다. 64KB 면 한 프레임에 200건 가까이 담겨도 된다.
	 */
	@Bean
	@ConditionalOnProperty(name = "finch.kis.realtime.enabled", havingValue = "true")
	WebSocketClient kisWebSocketClient() {
		WebSocketContainer container = ContainerProvider.getWebSocketContainer();
		container.setDefaultMaxTextMessageBufferSize(64 * 1024);
		return new StandardWebSocketClient(container);
	}

	@Bean
	@ConditionalOnProperty(name = "finch.kis.realtime.enabled", havingValue = "true")
	KisRealtimeFeed kisRealtimeFeed(WebSocketClient kisWebSocketClient, KisApprovalKeyClient approvalKeyClient,
		KisClient kisClient, KisKeyPool keyPool, PriceCache priceCache, LeaderLock leaderLock, KisProperties properties,
		MeterRegistry meterRegistry) {
		return new KisRealtimeFeed(kisWebSocketClient, approvalKeyClient, kisClient, keyPool, priceCache, leaderLock,
			properties, meterRegistry);
	}

	/** 실시간이 꺼진 구성. {@code KisRealtimeFeed} 가 있으면 그것이 {@link RealtimeCoverage} 라 이 빈은 만들어지지 않는다. */
	@Bean
	@ConditionalOnMissingBean(RealtimeCoverage.class)
	RealtimeCoverage noRealtimeCoverage() {
		return RealtimeCoverage.NONE;
	}
}
