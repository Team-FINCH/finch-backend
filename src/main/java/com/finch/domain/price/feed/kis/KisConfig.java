package com.finch.domain.price.feed.kis;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * KIS 빈들. <b>{@code finch.price.provider=kis} 일 때만</b> 만들어진다 — fake 면 키 풀도 토큰 관리자도 클라이언트도 없다.
 * 테스트·로컬은 fake 라 이 설정이 통째로 빠지고, KIS 코드는 {@code KisClientTest} 처럼 직접 조립해서 본다.
 * <p>
 * {@code WebClient.Builder} 는 프로토타입이라 토큰 관리자와 클라이언트가 각자 새 빌더를 받는다 ({@code WebClientConfig} 주석).
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
}
