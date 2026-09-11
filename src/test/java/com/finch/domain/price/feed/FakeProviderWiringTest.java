package com.finch.domain.price.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.feed.kis.KisClient;
import com.finch.domain.price.feed.kis.KisIndexFeed;
import com.finch.domain.price.feed.kis.KisPollingFeed;
import com.finch.domain.stock.port.CandleSourcePort;
import com.finch.domain.stock.port.EmptyCandleSourcePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** 기본(fake) 컨텍스트에는 KIS 빈이 하나도 없다 — 앱키 없이도 뜬다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FakeProviderWiringTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("provider=fake 면 FakePriceFeed·FakeIndexFeed 와 빈 일봉 포트뿐이고 KIS 빈은 없다")
	void wiresFake() {
		assertThat(context.getBean(PriceFeed.class)).isInstanceOf(FakePriceFeed.class);
		assertThat(context.getBean(IndexFeed.class)).isInstanceOf(FakeIndexFeed.class);
		assertThat(context.getBeanNamesForType(KisPollingFeed.class)).isEmpty();
		assertThat(context.getBeanNamesForType(KisIndexFeed.class)).isEmpty();
		assertThat(context.getBeanNamesForType(KisClient.class)).isEmpty();
		assertThat(context.getBean(CandleSourcePort.class)).isInstanceOf(EmptyCandleSourcePort.class);
	}
}
