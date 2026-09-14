package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.feed.RealtimeCoverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * {@code finch.kis.realtime.enabled=true} 스위치. 실시간 피드가 생기고 그것이 폴링의 {@link RealtimeCoverage} 가 된다.
 * 테스트 설정의 {@code auto-start: false} 라 감독 스레드는 돌지 않고 KIS 에 아무것도 보내지 않는다 — 종목 목록은 기본 설정의 30개다.
 */
@SpringBootTest(properties = {"finch.price.provider=kis", "finch.kis.realtime.enabled=true"})
@Import(TestcontainersConfiguration.class)
class KisRealtimeWiringTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("realtime.enabled=true 면 KisRealtimeFeed 가 있고 그것이 RealtimeCoverage 다. 기동 전이라 커버리지는 비어 있다")
	void wiresRealtime() {
		KisRealtimeFeed feed = context.getBean(KisRealtimeFeed.class);
		assertThat(feed.isRunning()).isFalse();
		assertThat(feed.covered()).isEmpty();
		assertThat(context.getBean(RealtimeCoverage.class)).isSameAs(feed);
		assertThat(context.getBean(KisProperties.class).realtime().codes()).hasSize(30).contains("005930", "047810");
		assertThat(context.getBeanNamesForType(KisApprovalKeyClient.class)).hasSize(1);
	}
}
