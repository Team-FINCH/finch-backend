package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 접속키 발급 요청의 모양(경로·본문 필드 이름)과 실패 분류. KIS 를 부르지 않고 응답만 흉내낸다 ({@code KisClientTest} 와 같은 방식). */
class KisApprovalKeyClientTest {

	private final List<ClientRequest> sent = new ArrayList<>();

	@Test
	@DisplayName("/oauth2/Approval 에 appkey·secretkey 로 POST 하고 approval_key 를 돌려준다")
	void issues() {
		KisApprovalKeyClient client = client(HttpStatus.OK, "{\"approval_key\":\"ak-1\"}");

		String issued = client.issue(new KisCredential("app-key", "app-secret", "t"));

		assertThat(issued).isEqualTo("ak-1");
		assertThat(sent).hasSize(1);
		assertThat(sent.getFirst().url().getPath()).endsWith(KisApprovalKeyClient.APPROVAL_PATH);
		assertThat(sent.getFirst().headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
	}

	@Test
	@DisplayName("거절(4xx)은 UNAUTHORIZED, 키가 빈 응답은 REJECTED 다")
	void classifiesFailures() {
		assertThatThrownBy(() -> client(HttpStatus.FORBIDDEN, "{\"error_code\":\"EGW00103\"}")
			.issue(new KisCredential("k", "s", "t")))
			.isInstanceOf(KisException.class)
			.extracting(e -> ((KisException) e).getKind()).isEqualTo(KisException.Kind.UNAUTHORIZED);

		assertThatThrownBy(() -> client(HttpStatus.OK, "{}").issue(new KisCredential("k", "s", "t")))
			.isInstanceOf(KisException.class)
			.extracting(e -> ((KisException) e).getKind()).isEqualTo(KisException.Kind.REJECTED);
	}

	private KisApprovalKeyClient client(HttpStatus status, String body) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			sent.add(request);
			return Mono.just(ClientResponse.create(status)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build());
		});
		KisProperties props = new KisProperties("https://kis.test", List.of(), Duration.ofSeconds(3), 20,
			Duration.ofSeconds(5), Duration.ofMinutes(5), false);
		return new KisApprovalKeyClient(builder, props);
	}
}
