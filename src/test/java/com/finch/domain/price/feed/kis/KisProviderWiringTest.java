package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.feed.FakeIndexFeed;
import com.finch.domain.price.feed.FakePriceFeed;
import com.finch.domain.price.feed.IndexFeed;
import com.finch.domain.price.feed.PriceFeed;
import com.finch.domain.price.feed.RealtimeCoverage;
import com.finch.domain.stock.port.CandleSourcePort;
import com.finch.domain.stock.port.EmptyCandleSourcePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * {@code finch.price.provider=kis} 스위치. 공급자가 KIS 로 바뀌고 Fake 가 사라지며, 일봉 원천 포트가 KIS 어댑터로 채워진다.
 * 테스트 설정의 {@code auto-start: false} 라 폴링은 돌지 않고 KIS 에 아무것도 보내지 않는다.
 * 기본(fake) 컨텍스트의 반대 상태는 {@code FakeProviderWiringTest} 가 본다.
 */
@SpringBootTest(properties = "finch.price.provider=kis")
@Import(TestcontainersConfiguration.class)
class KisProviderWiringTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("provider=kis 면 KisPollingFeed·KisIndexFeed·KisClient·KisCandleSourceAdapter 가 있고 Fake·빈 포트는 없다")
	void wiresKis() {
		assertThat(context.getBean(PriceFeed.class)).isInstanceOf(KisPollingFeed.class);
		assertThat(context.getBeanNamesForType(FakePriceFeed.class)).isEmpty();
		assertThat(context.getBean(IndexFeed.class)).isInstanceOf(KisIndexFeed.class);
		assertThat(context.getBeanNamesForType(FakeIndexFeed.class)).isEmpty();
		assertThat(context.getBean(KisIndexFeed.class).isRunning()).isFalse();
		assertThat(context.getBean(CandleSourcePort.class)).isInstanceOf(KisCandleSourceAdapter.class);
		assertThat(context.getBeanNamesForType(EmptyCandleSourcePort.class)).isEmpty();
		assertThat(context.getBean(KisKeyPool.class).size()).isEqualTo(1);
		assertThat(context.getBean(KisKeyPool.class).keys().getFirst().label()).isEqualTo("test");
		assertThat(context.getBean(KisPollingFeed.class).isRunning()).isFalse();
		// 실시간 티어는 realtime.enabled 가 따로 켜져야 한다. 기본은 꺼져 있어 폴링이 전부 맡는다.
		assertThat(context.getBeanNamesForType(KisRealtimeFeed.class)).isEmpty();
		assertThat(context.getBean(RealtimeCoverage.class)).isSameAs(RealtimeCoverage.NONE);
	}
}
