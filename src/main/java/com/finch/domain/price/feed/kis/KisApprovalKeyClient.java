package com.finch.domain.price.feed.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 웹소켓 접속키({@code approval_key}) 발급. REST 접근토큰({@link KisTokenManager})과는 별개의 키이고 엔드포인트도 다르다
 * ({@code /oauth2/Approval}, 본문 필드가 {@code secretkey} 다 — 토큰 발급의 {@code appsecret} 과 이름이 다르다).
 * <p>
 * <b>보관하지 않는다.</b> 접속키는 세션을 열 때 한 번 쓰고 끝이라 재접속마다 새로 받는다. 토큰처럼 분당 1회 제한이 문서에 없고,
 * 재접속은 드문 일이라 Redis 에 나눠 둘 이유가 없다. 제한이 실측에서 드러나면 그때 {@code KisTokenManager} 와 같은 구조로 옮긴다.
 */
@Slf4j
public class KisApprovalKeyClient {

	static final String APPROVAL_PATH = "/oauth2/Approval";

	private final WebClient webClient;
	private final KisProperties properties;

	public KisApprovalKeyClient(WebClient.Builder builder, KisProperties properties) {
		this.webClient = builder.baseUrl(properties.baseUrl()).build();
		this.properties = properties;
	}

	public String issue(KisCredential key) {
		ApprovalRes res;
		try {
			res = webClient.post()
				.uri(APPROVAL_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("grant_type", "client_credentials", "appkey", key.appKey(), "secretkey", key.appSecret()))
				.retrieve()
				.onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class).defaultIfEmpty("")
					.map(body -> new KisException(KisException.Kind.UNAUTHORIZED,
						"KIS 접속키 발급 거절 key=" + key.label() + " status=" + response.statusCode() + " body=" + body)))
				.bodyToMono(ApprovalRes.class)
				.block(properties.timeout());
		} catch (KisException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new KisException(KisException.Kind.UNAVAILABLE, "KIS 접속키 발급 실패 key=" + key.label(), e);
		}
		if (res == null || res.approvalKey() == null || res.approvalKey().isBlank()) {
			throw new KisException(KisException.Kind.REJECTED, "KIS 접속키 응답에 approval_key 가 없다 key=" + key.label());
		}
		log.info("KIS 웹소켓 접속키 발급 key={}", key.label());
		return res.approvalKey();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ApprovalRes(@JsonProperty("approval_key") String approvalKey) {
	}
}
