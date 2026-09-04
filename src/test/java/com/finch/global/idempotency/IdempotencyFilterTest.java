package com.finch.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.TestcontainersConfiguration;
import com.finch.global.security.JwtProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 멱등성 계약(apiSpec 1.4)의 갈래들을 실제 필터 체인과 Redis 로 확인한다.
 * <p>
 * 단위 테스트로 나누지 않은 이유 — 이 기능의 핵심은 "응답 본문을 붙잡았다가 다시 쓴다"이고, 그것이
 * 성립하는지는 <b>서블릿 응답이 실제로 감싸졌을 때만</b> 알 수 있다. 목으로는 감싸기 자체를 흉내 내게
 * 되어 검증하려는 것을 검증하지 못한다.
 * <p>
 * 검사 대상 경로는 {@code finch.idempotency.paths} 를 테스트 전용 값으로 덮어 아래 더미 컨트롤러를
 * 가리킨다. 실제 {@code POST /orders} 를 쓰지 않는 이유는 그 엔드포인트가 아직 없기도 하지만,
 * <b>필터는 어느 도메인도 알지 못해야</b> 하기 때문이다 — 주문에 묶어 두면 그 전제가 흐려진다.
 */
@SpringBootTest(properties = {
	"finch.idempotency.paths[0]=/test/idempotent/**",
	// 처리 중 표시가 테스트 도중 만료되면 IN_PROGRESS 갈래를 볼 수 없다.
	"finch.idempotency.in-progress-ttl=60s",
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IdempotencyFilterTest.TestEndpoints.class})
class IdempotencyFilterTest {

	private static final long USER_ID = 42L;
	private static final String PATH = "/test/idempotent";
	private static final String FAILING_PATH = "/test/idempotent/fail";
	private static final String BODY = "{\"amount\":1000}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtProvider jwtProvider;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private TestEndpoints endpoints;

	@BeforeEach
	void reset() {
		endpoints.calls.set(0);
		// 테스트마다 같은 키를 써도 앞 테스트의 장부가 남지 않게 한다.
		Set<String> keys = redisTemplate.keys("idem:*");
		if (!keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}

	@Test
	@DisplayName("Idempotency-Key 가 없으면 400 IDEMPOTENCY_KEY_REQUIRED — 컨트롤러는 돌지 않는다")
	void missingKeyIsRejected() throws Exception {
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		assertThat(endpoints.calls).hasValue(0);
	}

	@Test
	@DisplayName("처음 보는 키는 그대로 처리한다")
	void firstRequestIsProcessed() throws Exception {
		mockMvc.perform(request("key-1", BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sequence").value(1));

		assertThat(endpoints.calls).hasValue(1);
	}

	/**
	 * 계약의 핵심이다. 같은 상태 코드와 같은 본문이 나가야 하고, <b>컨트롤러는 한 번만 돌아야</b> 한다.
	 * 응답에 호출 순번을 담은 이유가 그것이다 — 재실행되면 순번이 2 가 되어 즉시 드러난다.
	 */
	@Test
	@DisplayName("같은 키·같은 본문은 재처리하지 않고 최초 응답을 그대로 재생한다")
	void replaysFirstResponse() throws Exception {
		mockMvc.perform(request("key-1", BODY)).andExpect(status().isCreated());

		mockMvc.perform(request("key-1", BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sequence").value(1));

		assertThat(endpoints.calls).hasValue(1);
	}

	@Test
	@DisplayName("같은 키에 다른 본문이면 409 IDEMPOTENCY_CONFLICT — 클라이언트 버그 신호다")
	void differentBodyOnSameKeyConflicts() throws Exception {
		mockMvc.perform(request("key-1", BODY)).andExpect(status().isCreated());

		mockMvc.perform(request("key-1", "{\"amount\":9999}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		assertThat(endpoints.calls).hasValue(1);
	}

	@Test
	@DisplayName("앞선 처리가 아직 안 끝났으면 409 IDEMPOTENCY_IN_PROGRESS")
	void inProgressKeyConflicts() throws Exception {
		// 다른 요청이 방금 키를 잡은 상태를 장부에 직접 만든다.
		// 실제 동시 요청으로 이 상태를 노리면 타이밍에 기대게 되어 테스트가 불안정해진다.
		markInProgress("key-1", sha256Of(BODY));

		mockMvc.perform(request("key-1", BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));

		assertThat(endpoints.calls).hasValue(0);
	}

	/**
	 * 계약이 "짧게 대기 후 동일 키로 재시도"(apiSpec 1.4)인데 그 "짧게"를 클라이언트가 정하고 있었다.
	 * 프론트 리뷰 요청으로 서버가 값을 내려주기로 했다 (apiSpec v0.7.1).
	 */
	@Test
	@DisplayName("409 IDEMPOTENCY_IN_PROGRESS 에는 Retry-After 가 실린다 — 재시도 간격을 서버가 정한다")
	void inProgressCarriesRetryAfter() throws Exception {
		markInProgress("key-1", sha256Of(BODY));

		mockMvc.perform(request("key-1", BODY))
			.andExpect(status().isConflict())
			.andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
	}

	/** 재시도해도 소용없는 갈래에는 붙이지 않는다. 붙이면 클라이언트가 재시도해도 된다고 읽는다. */
	@Test
	@DisplayName("IDEMPOTENCY_CONFLICT 에는 Retry-After 를 붙이지 않는다 — 재시도 금지 신호다")
	void conflictHasNoRetryAfter() throws Exception {
		mockMvc.perform(request("key-1", BODY)).andExpect(status().isCreated());

		mockMvc.perform(request("key-1", "{\"amount\":9999}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
			.andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
	}

	/**
	 * 서버 잘못으로 끝난 요청은 장부에서 지워야 한다. 남겨 두면 같은 키로는 영영 재시도할 수 없는데,
	 * 클라이언트는 실패한 클릭을 <b>같은 키로</b> 다시 보내도록 되어 있다 (apiSpec 1.4).
	 */
	@Test
	@DisplayName("5xx 로 끝난 요청은 키를 놓아 줘서 같은 키로 재시도할 수 있다")
	void serverErrorReleasesKey() throws Exception {
		mockMvc.perform(request(FAILING_PATH, "key-1", BODY)).andExpect(status().isInternalServerError());
		mockMvc.perform(request(FAILING_PATH, "key-1", BODY)).andExpect(status().isInternalServerError());

		// 재생됐다면 1 이다. 2 는 두 번째 요청이 컨트롤러까지 다시 갔다는 뜻이다.
		assertThat(endpoints.calls).hasValue(2);
	}

	/**
	 * 사용자가 주문 버튼을 연타했을 때의 모습이다. 몇 건이 409 를 받고 몇 건이 재생을 받는지는
	 * 타이밍에 따라 갈리지만, <b>컨트롤러가 한 번만 도는 것</b>은 타이밍과 무관하게 성립해야 한다.
	 * 그 보장은 Redis {@code SET NX} 한 번으로 판정하는 데서 온다 ({@code IdempotencyStore.begin}).
	 */
	@Test
	@DisplayName("같은 키로 10건이 동시에 와도 컨트롤러는 한 번만 돈다")
	void concurrentRequestsRunControllerOnce() throws Exception {
		int concurrency = 10;
		Callable<Integer> call = () -> mockMvc.perform(request("burst", BODY)).andReturn().getResponse().getStatus();

		List<Integer> statuses;
		try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
			List<Future<Integer>> futures = pool.invokeAll(Collections.nCopies(concurrency, call));
			statuses = futures.stream().map(IdempotencyFilterTest::valueOf).toList();
		}

		assertThat(endpoints.calls).hasValue(1);
		// 201 은 최초 처리이거나 그 응답의 재생이고, 409 는 아직 처리 중이라 기다리라는 답이다.
		assertThat(statuses).allMatch(status -> status == 201 || status == 409);
		assertThat(statuses).contains(201);
	}

	@Test
	@DisplayName("검사 대상이 아닌 경로는 키 없이도 통과한다 — 필터가 전 경로에 걸리지 않는다")
	void untrackedPathIsUnaffected() throws Exception {
		mockMvc.perform(post("/test/plain").contentType(MediaType.APPLICATION_JSON).content(BODY)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("미인증 요청은 키가 없어도 400 이 아니라 401 이다 — 인증 실패가 가려지면 안 된다")
	void unauthenticatedRequestGets401() throws Exception {
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isUnauthorized());

		assertThat(endpoints.calls).hasValue(0);
	}

	private RequestBuilder request(String key, String body) {
		return request(PATH, key, body);
	}

	private RequestBuilder request(String path, String key, String body) {
		return post(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header(HttpHeaders.AUTHORIZATION, bearer())
			.header(IdempotencyFilter.HEADER, key);
	}

	private String bearer() {
		return "Bearer " + jwtProvider.createAccessToken(USER_ID);
	}

	/** 장부에 "처리 중"을 직접 심는다. 저장 형식이 바뀌면 이 문자열도 함께 바뀐다. */
	private void markInProgress(String key, String bodyHash) {
		redisTemplate.opsForValue().set("idem:" + USER_ID + ":" + key,
			"{\"state\":\"IN_PROGRESS\",\"bodyHash\":\"" + bodyHash + "\"}", Duration.ofSeconds(60));
	}

	private static String sha256Of(String body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static int valueOf(Future<Integer> future) {
		try {
			return future.get();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 필터를 검증하기 위한 최소 엔드포인트. 도메인 컨트롤러를 쓰지 않는 이유 — 그러면 그 도메인의
	 * 검증·서비스가 함께 얽혀서 실패했을 때 원인이 필터인지 도메인인지 가릴 수 없다.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class TestEndpoints {

		private final AtomicInteger calls = new AtomicInteger();

		@Bean
		TestController testController() {
			return new TestController(calls);
		}
	}

	/**
	 * {@link TestEndpoints} 안에 중첩하지 않는다. {@code @Configuration} 의 중첩 클래스는 스프링이
	 * 별도 빈으로도 등록하려 들어서, 생성자 인자({@code AtomicInteger})를 주입받지 못해 컨텍스트가 죽는다.
	 */
	@RestController
	record TestController(AtomicInteger calls) {

		/** 응답에 호출 순번을 담는다. 재생이 아니라 재실행이면 이 값이 늘어 즉시 드러난다. */
		@PostMapping("/test/idempotent")
		ResponseEntity<Map<String, Object>> create(@RequestBody String body) {
			return ResponseEntity.status(201).body(Map.of("sequence", calls.incrementAndGet(), "echo", body));
		}

		/** 서버 잘못으로 끝나는 경로. GlobalExceptionHandler 가 500 INTERNAL_ERROR 로 바꾼다. */
		@PostMapping("/test/idempotent/fail")
		Map<String, Object> fail(@RequestBody String body) {
			calls.incrementAndGet();
			throw new IllegalStateException("의도한 실패");
		}

		/** 멱등성 검사 대상이 아닌 경로. */
		@PostMapping("/test/plain")
		Map<String, Object> plain(@RequestBody String body) {
			return Map.of("ok", true);
		}
	}
}
