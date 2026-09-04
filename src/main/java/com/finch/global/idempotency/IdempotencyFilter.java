package com.finch.global.idempotency;

import com.finch.global.apiPayload.ErrorResponse;
import com.finch.global.apiPayload.code.BaseErrorCode;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.idempotency.IdempotencyStore.Snapshot;
import com.finch.global.idempotency.IdempotencyStore.State;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * 같은 {@code Idempotency-Key} 로 두 번 온 요청을 한 번만 실행하고, 두 번째에는 최초 응답을 그대로
 * 돌려준다 (apiSpec 1.4).
 * <p>
 * <b>인터셉터가 아니라 필터인 이유.</b> 계약의 핵심이 "재처리하지 않고 <b>최초 결과를 그대로 반환</b>"이라
 * 응답 본문을 붙잡아 두었다가 다시 써야 한다. {@code HandlerInterceptor} 의 {@code postHandle} 은
 * 컨트롤러가 반환한 <b>뒤</b>, 메시지 컨버터가 이미 본문을 응답 스트림에 쓴 시점에 불린다 — 그 자리에서는
 * 본문을 읽을 수도, 바꿔 쓸 수도 없다. 응답을 {@link ContentCachingResponseWrapper} 로 감싸는 것은
 * 체인 바깥에 있는 필터만 할 수 있다. 요청 본문 해시도 마찬가지로 컨트롤러가 읽기 전에 떠야 한다.
 * <p>
 * <b>자리는 Spring Security 체인 전체의 뒤</b>다. Redis 키가 사용자별로 갈리므로
 * ({@link IdempotencyStore}) 인증이 끝난 뒤여야 사용자를 알 수 있다. 보통의 {@code Filter} 빈은
 * 시큐리티 체인({@code springSecurityFilterChain}, 순서 -100)보다 낮은 우선순위로 등록되어 그 안쪽에서
 * 도는데, 그 자리가 정확히 우리가 원하는 곳이다 — 인증·인가가 모두 끝났고 컨트롤러는 아직이다.
 * <p>
 * 빈으로 만드는 자리와 그 이유는 {@link IdempotencyConfig} 에 있다.
 * <p>
 * 이 자리의 대가로 미인증 요청은 여기까지 오지 않는다 — 인가 단계에서 이미 401 로 끝난다. 그래도
 * {@code currentUserId()} 의 null 검사는 남겨 둔다. 나중에 무인증 경로가 검사 대상에 들어오면
 * 키 없이 400 을 내는 것보다 조용히 통과하는 쪽이 맞다.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

	public static final String HEADER = "Idempotency-Key";

	private final IdempotencyStore store;
	private final ObjectMapper objectMapper;
	private final List<PathPattern> paths;

	public IdempotencyFilter(IdempotencyStore store, ObjectMapper objectMapper, IdempotencyProperties properties) {
		this.store = store;
		this.objectMapper = objectMapper;
		PathPatternParser parser = PathPatternParser.defaultInstance;
		this.paths = properties.paths().stream().map(parser::parse).toList();
	}

	/**
	 * 설정에 적힌 경로의 POST 만 검사한다.
	 * <p>
	 * 메서드를 POST 로 못 박는 이유 — 멱등성 키가 필요한 것은 "부를 때마다 새 자원이 생기는" 요청뿐이다.
	 * GET·PUT·DELETE 는 같은 요청을 두 번 보내도 결과가 같은 것이 HTTP 규격의 약속이라 키가 필요 없다.
	 * 그래서 설정에는 경로만 적고 메서드는 여기서 정한다 — 설정에 메서드를 함께 적게 하면 오타로
	 * 대상에서 빠지는 경로가 생기고, 그건 <b>조용히 멱등성이 없는 상태</b>다.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		PathContainer path = PathContainer.parsePath(request.getRequestURI());
		return paths.stream().noneMatch(pattern -> pattern.matches(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		Long userId = currentUserId();
		if (userId == null) {
			chain.doFilter(request, response);
			return;
		}

		String key = request.getHeader(HEADER);
		// 헤더 검사는 본문 검증보다 앞이다 (apiSpec 11.1). 필터라서 자연히 그렇게 되지만,
		// 순서가 뒤집히면 키를 빠뜨린 요청이 INVALID_REQUEST 를 받아 원인이 가려진다.
		if (!StringUtils.hasText(key)) {
			write(response, GeneralErrorCode.IDEMPOTENCY_KEY_REQUIRED);
			return;
		}

		// 본문을 통째로 읽어 해시를 뜬다. 스트림은 한 번만 읽히므로 읽은 바이트를 들고 다시 흘려보낸다.
		byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
		String bodyHash = sha256(body);

		Optional<Snapshot> existing = store.begin(userId, key, bodyHash);
		if (existing.isPresent()) {
			respondToDuplicate(response, existing.get(), bodyHash);
			return;
		}

		execute(new CachedBodyRequest(request, body), response, chain, userId, key, bodyHash);
	}

	/**
	 * 처리 주체가 된 요청. 응답을 감싸 두었다가 장부에 적고, 같은 내용을 실제 응답으로 흘려보낸다.
	 * <p>
	 * <b>5xx 와 예외는 장부에서 지운다.</b> 서버 잘못으로 끝난 것을 저장해 두면 같은 키로는 영영
	 * 재시도할 수 없다. 4xx 는 남긴다 — 같은 요청을 다시 보내도 같은 이유로 거절되는 것이 맞고,
	 * 그것이 "동일 상태 코드로 최초 결과 반환"(apiSpec 1.4)이다.
	 */
	private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
		long userId, String key, String bodyHash) throws ServletException, IOException {
		ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
		boolean recorded = false;
		try {
			chain.doFilter(request, wrapper);

			int status = wrapper.getStatus();
			if (status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
				store.release(userId, key);
			} else {
				String responseBody = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
				store.complete(userId, key, bodyHash, status, wrapper.getContentType(), responseBody);
			}
			recorded = true;
		} finally {
			// 예외가 체인을 뚫고 나가면 응답 상태를 볼 수 없다. 서버 잘못으로 보고 지운다.
			if (!recorded) {
				store.release(userId, key);
			}
			// 감싼 응답은 여기서 풀지 않으면 본문이 클라이언트에게 나가지 않는다.
			wrapper.copyBodyToResponse();
		}
	}

	/** 이미 같은 키가 장부에 있는 요청. 세 갈래뿐이고 판정 근거는 상태와 본문 해시다 (apiSpec 1.4). */
	private void respondToDuplicate(HttpServletResponse response, Snapshot snapshot, String bodyHash)
		throws IOException {
		if (snapshot.state() == State.IN_PROGRESS) {
			write(response, GeneralErrorCode.IDEMPOTENCY_IN_PROGRESS);
			return;
		}
		if (!snapshot.bodyHash().equals(bodyHash)) {
			write(response, GeneralErrorCode.IDEMPOTENCY_CONFLICT);
			return;
		}
		replay(response, snapshot);
	}

	/** 최초 응답을 그대로 다시 쓴다. 컨트롤러를 부르지 않는 것이 이 필터의 존재 이유다. */
	private static void replay(HttpServletResponse response, Snapshot snapshot) throws IOException {
		response.setStatus(snapshot.status());
		if (snapshot.contentType() != null) {
			response.setContentType(snapshot.contentType());
		}
		if (StringUtils.hasLength(snapshot.body())) {
			response.getOutputStream().write(snapshot.body().getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * 필터에서 던진 예외는 {@code GlobalExceptionHandler} 를 타지 않는다 — 그건
	 * {@code @RestControllerAdvice} 라 DispatcherServlet 안에서만 돈다. 그래서 본문을 직접 쓴다.
	 * 형식은 {@link ErrorResponse} 로 맞춘다. 프론트가 400 을 두 가지 모양으로 파싱하지 않게 하려는 것이다.
	 */
	private void write(HttpServletResponse response, BaseErrorCode errorCode) throws IOException {
		response.setStatus(errorCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		// 지정하지 않으면 컨테이너 기본 인코딩으로 나가 한글 message 가 깨진다.
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
	}

	/** {@code JwtAuthenticationFilter} 가 principal 에 넣은 값. 없으면 미인증 요청이다. */
	private static Long currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof Long userId ? userId : null;
	}

	/**
	 * 본문 해시는 <b>원문 바이트</b>로 뜬다. JSON 정규화(키 순서·공백 정리)를 하지 않는 이유 — 같은 클릭의
	 * 재시도는 같은 바이트를 보낸다. 정규화는 "다른 요청인데 같다고 판정할" 여지만 만들고,
	 * 그 오판의 결과는 <b>사용자가 의도한 두 번째 주문이 조용히 무시되는 것</b>이다.
	 */
	private static String sha256(byte[] body) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 은 모든 JVM 구현이 제공해야 하는 알고리즘이다 (MessageDigest 규격).
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 이미 읽어 버린 본문을 컨트롤러가 다시 읽게 해 준다.
	 * <p>
	 * {@code ContentCachingRequestWrapper} 를 쓰지 않은 이유 — 그건 <b>뒤에서 읽는 만큼만</b> 캐시에
	 * 채운다. 우리는 컨트롤러보다 <b>먼저</b> 전체 본문이 필요하므로 순서가 맞지 않는다.
	 */
	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		private CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream source = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public boolean isFinished() {
					return source.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException("비동기 읽기를 쓰지 않는다");
				}

				@Override
				public int read() {
					return source.read();
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}
	}
}
