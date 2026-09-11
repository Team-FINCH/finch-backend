package com.finch.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.ai.exception.AiErrorCode;
import com.finch.domain.ai.relay.AiRelayService;
import com.finch.domain.ai.relay.AiRoute;
import com.finch.global.exception.CustomException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림함이 쓰는 논지 목록의 캐시 규칙과, 논지를 쓰는 중계가 캐시를 지우는지 본다 (apiSpec 6.4 · 10.1). 캐시는 컨테이너의 진짜 Redis 이고
 * AI 중계만 목이다. 시각은 서비스마다 고정 {@code Clock} 으로 준다 — 5분 경계를 기다리지 않는다.
 * <p>
 * 사용자 id 를 테스트마다 새로 뽑는다. 캐시 키가 사용자별이라 Redis 를 비우지 않아도 섞이지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WikiThesisServiceTest {

	private static final AtomicLong USER_SEQ = new AtomicLong(960_000_000L);
	private static final Instant T0 = Instant.parse("2026-09-11T05:00:00Z");
	private static final String WIKI_JSON = """
		{"content":{"profile":[],"theses":[
		  {"ticker":"000660","name":"SK하이닉스","status":"active"},
		  {"ticker":"005930","name":"삼성전자","status":"closed"}]}}""";

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final AiRelayService relay = mock(AiRelayService.class);

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Nested
	@DisplayName("논지 목록")
	class ActiveTheses {

		@Test
		@DisplayName("active 논지의 종목만 모으고, 5분 안에는 AI 를 다시 부르지 않는다")
		void filtersActiveAndCaches() {
			long userId = USER_SEQ.incrementAndGet();
			givenWiki(userId, WIKI_JSON);

			assertThat(serviceAt(T0).activeThesisTickers(userId)).contains(Set.of("000660"));
			assertThat(serviceAt(T0.plus(Duration.ofMinutes(4))).activeThesisTickers(userId)).contains(Set.of("000660"));

			verify(relay, times(1)).relay(eq(AiRoute.WIKI), isNull(), isNull(), eq(userId), isNull());
		}

		@Test
		@DisplayName("5분이 지나면 다시 읽는다")
		void refetchesAfterFreshWindow() {
			long userId = USER_SEQ.incrementAndGet();
			givenWiki(userId, WIKI_JSON);

			serviceAt(T0).activeThesisTickers(userId);
			serviceAt(T0.plus(Duration.ofMinutes(WikiThesisService.FRESH_MINUTES)).plusSeconds(1)).activeThesisTickers(userId);

			verify(relay, times(2)).relay(eq(AiRoute.WIKI), isNull(), isNull(), eq(userId), isNull());
		}

		/** 신선 기간이 지났어도 값은 남아 있다. AI 가 죽었을 때 그 값으로 버틴다. */
		@Test
		@DisplayName("다시 읽기에 실패하면 남아 있는 옛 값을 쓴다")
		void fallsBackToStaleValue() {
			long userId = USER_SEQ.incrementAndGet();
			givenWiki(userId, WIKI_JSON);
			serviceAt(T0).activeThesisTickers(userId);

			given(relay.relay(eq(AiRoute.WIKI), isNull(), isNull(), eq(userId), isNull()))
				.willThrow(new CustomException(AiErrorCode.AI_UPSTREAM_UNAVAILABLE));

			assertThat(serviceAt(T0.plus(Duration.ofMinutes(30))).activeThesisTickers(userId)).contains(Set.of("000660"));
		}

		@Test
		@DisplayName("읽기에 실패했는데 남은 값도 없으면 empty — 예외를 밖으로 내지 않는다")
		void emptyWhenNothingKnown() {
			long userId = USER_SEQ.incrementAndGet();
			given(relay.relay(eq(AiRoute.WIKI), isNull(), isNull(), eq(userId), isNull()))
				.willThrow(new CustomException(AiErrorCode.AI_UPSTREAM_TIMEOUT));

			assertThat(serviceAt(T0).activeThesisTickers(userId)).isEmpty();
		}

		/** 빈 목록으로 두면 보유 종목 전부에 "왜 담으셨나요?" 가 뜬다. 모양이 바뀐 응답은 모르는 것으로 본다. */
		@Test
		@DisplayName("응답에 content.theses 가 없으면 실패로 본다 — 빈 목록으로 두지 않는다")
		void missingThesesIsFailure() {
			long userId = USER_SEQ.incrementAndGet();
			givenWiki(userId, "{\"content\":{\"profile\":[]}}");

			assertThat(serviceAt(T0).activeThesisTickers(userId)).isEmpty();
		}
	}

	@Nested
	@DisplayName("논지 쓰기 중계")
	class Writes {

		@Test
		@DisplayName("POST 가 성공하면 캐시를 지운다 — 다음 조회가 AI 를 다시 읽는다")
		void createEvicts() {
			long userId = USER_SEQ.incrementAndGet();
			givenWiki(userId, WIKI_JSON);
			WikiThesisService service = serviceAt(T0);
			service.activeThesisTickers(userId);
			JsonNode body = mapper.readTree("{\"ticker\":\"005930\",\"text\":\"x\"}");
			given(relay.relay(eq(AiRoute.WIKI_THESIS_CREATE), isNull(), isNull(), eq(userId), eq(body)))
				.willReturn(ResponseEntity.ok(mapper.readTree("{\"content\":{}}")));

			service.create(userId, body);

			assertThat(redisTemplate.hasKey(WikiThesisService.KEY_PREFIX + userId)).isFalse();
			service.activeThesisTickers(userId);
			verify(relay, times(2)).relay(eq(AiRoute.WIKI), isNull(), isNull(), eq(userId), isNull());
		}

		@Test
		@DisplayName("PUT 도 성공하면 캐시를 지우고, 경로 변수는 ticker 로 넘긴다")
		void updateEvicts() {
			long userId = USER_SEQ.incrementAndGet();
			givenWiki(userId, WIKI_JSON);
			WikiThesisService service = serviceAt(T0);
			service.activeThesisTickers(userId);
			given(relay.relay(eq(AiRoute.WIKI_THESIS_UPDATE), eq(Map.of("ticker", "000660")), isNull(), eq(userId), any()))
				.willReturn(ResponseEntity.ok(mapper.readTree("{\"content\":{}}")));

			service.update(userId, "000660", mapper.readTree("{\"text\":\"y\"}"));

			assertThat(redisTemplate.hasKey(WikiThesisService.KEY_PREFIX + userId)).isFalse();
		}

		@Test
		@DisplayName("중계가 실패하면 예외가 그대로 나가고 캐시는 남는다")
		void failedWriteKeepsCache() {
			long userId = USER_SEQ.incrementAndGet();
			givenWiki(userId, WIKI_JSON);
			WikiThesisService service = serviceAt(T0);
			service.activeThesisTickers(userId);
			given(relay.relay(eq(AiRoute.WIKI_THESIS_CREATE), isNull(), isNull(), eq(userId), any()))
				.willThrow(new CustomException(AiErrorCode.AI_UPSTREAM_UNAVAILABLE));

			assertThatThrownBy(() -> service.create(userId, mapper.readTree("{}"))).isInstanceOf(CustomException.class);

			assertThat(redisTemplate.hasKey(WikiThesisService.KEY_PREFIX + userId)).isTrue();
			assertThat(service.activeThesisTickers(userId)).isEqualTo(Optional.of(Set.of("000660")));
		}
	}

	private void givenWiki(long userId, String json) {
		given(relay.relay(eq(AiRoute.WIKI), isNull(), isNull(), eq(userId), isNull()))
			.willReturn(ResponseEntity.ok(mapper.readTree(json)));
	}

	private WikiThesisService serviceAt(Instant now) {
		return new WikiThesisService(relay, redisTemplate, Clock.fixed(now, ZoneOffset.UTC));
	}
}
