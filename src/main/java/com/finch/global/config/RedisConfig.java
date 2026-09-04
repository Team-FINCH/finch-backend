package com.finch.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 접근 템플릿. 접속 정보는 {@code spring.data.redis.*} 가 갖고 여기서는 <b>직렬화만</b> 정한다.
 * <p>
 * {@code StringRedisTemplate} 은 Boot 가 자동 구성하므로 다시 선언하지 않는다. 문자열 하나만 넣고 빼는
 * 곳({@code RefreshTokenStore}, 멱등성 장부)은 그것을 그대로 쓴다 — 값이 사람이 읽을 수 있는 형태로
 * 남아 redis-cli 로 바로 확인된다.
 * <p>
 * 여기서 더하는 것은 <b>객체를 JSON 으로 넣는 템플릿</b> 하나다. 시세 캐시(apiSpec 5.4)처럼 필드 여러 개를
 * 한 키에 담는 쪽이 쓴다. 기본 {@code JdkSerializationRedisSerializer} 를 쓰지 않는 이유가 두 개다.
 * <ol>
 *   <li>값이 자바 직렬화 바이트라 redis-cli 로 읽을 수 없다. 시세가 안 맞을 때 캐시를 눈으로 못 본다.</li>
 *   <li>클래스가 바뀌면 이전에 넣은 값을 역직렬화하지 못한다. 배포할 때마다 캐시가 죽는다.</li>
 * </ol>
 */
@Configuration
public class RedisConfig {

	/**
	 * 키는 문자열, 값은 JSON 이다. 해시 필드도 같은 규칙을 쓴다 — 시세 캐시가 해시라 여기서 함께 정한다.
	 * <p>
	 * 직렬화기를 {@code builder()} 로 만들고 <b>애플리케이션의 {@code ObjectMapper} 를 넘기지 않는다.</b>
	 * 그 매퍼는 HTTP 응답 형식(빈 필드 제외, 날짜 표기)에 맞춰져 있고, 캐시는 넣은 그대로 되받는 것이
	 * 목적이라 요구가 다르다. 하나를 공유하면 응답 형식을 고칠 때 캐시 호환성이 조용히 깨진다.
	 * <p>
	 * 기본 타입 정보(default typing)를 켜지 않는다. 켜면 JSON 에 클래스 FQCN 이 박혀 패키지를 옮기는
	 * 순간 기존 값을 못 읽고, 역직렬화 대상이 넓어져 공격 표면도 는다. 대신 읽는 쪽이 타입을 지정한다.
	 */
	@Bean
	RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
		StringRedisSerializer keySerializer = new StringRedisSerializer();
		GenericJacksonJsonRedisSerializer valueSerializer = GenericJacksonJsonRedisSerializer.builder().build();

		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(keySerializer);
		template.setHashKeySerializer(keySerializer);
		template.setValueSerializer(valueSerializer);
		template.setHashValueSerializer(valueSerializer);
		return template;
	}
}
