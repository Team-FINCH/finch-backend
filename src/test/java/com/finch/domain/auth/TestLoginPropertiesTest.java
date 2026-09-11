package com.finch.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 운영에서 켜는 방법이 환경변수 두 개뿐이라(application.yaml 에 자리표시자가 없다) <b>그 이름이 실제로 설정에 묶이는지</b>를 고정한다.
 * 이름이 틀리면 운영에서 켠 줄 알았는데 조용히 꺼진 채로 뜬다 — 경로는 404 이고 로그에도 신호가 없다.
 */
class TestLoginPropertiesTest {

	@Test
	@DisplayName("FINCH_AUTH_TESTLOGIN_ENABLED · FINCH_AUTH_TESTLOGIN_KEY 가 finch.auth.test-login 에 묶인다")
	void environmentVariablesBind() {
		StandardEnvironment environment = new StandardEnvironment();
		// 이름이 "...systemEnvironment" 여야 스프링이 환경변수 이름 규칙(대문자·밑줄·하이픈 제거)으로 읽는다. 운영의 환경변수가
		// 들어가는 소스가 바로 그 이름(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)이다.
		environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
			"test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
			"FINCH_AUTH_TESTLOGIN_ENABLED", "true",
			"FINCH_AUTH_TESTLOGIN_KEY", "env-test-login-key-0123")));
		ConfigurationPropertySources.attach(environment);

		// @ConditionalOnProperty 가 보는 경로 — 이것이 "true" 여야 컨트롤러·서비스 빈이 생긴다.
		assertThat(environment.getProperty("finch.auth.test-login.enabled")).isEqualTo("true");
		TestLoginProperties properties = Binder.get(environment)
			.bind("finch.auth.test-login", TestLoginProperties.class).get();
		assertThat(properties.enabled()).isTrue();
		assertThat(properties.key()).isEqualTo("env-test-login-key-0123");
	}
}
