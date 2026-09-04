package com.finch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} 을 붙이면 {@code @ConfigurationProperties} 클래스가 자기 자리에서
 * 빈이 된다. 이것이 없으면 설정 클래스를 하나 만들 때마다 어딘가의 {@code @EnableConfigurationProperties}
 * 목록에 이름을 더해야 하고, 빠뜨리면 <b>기동은 되는데 값이 전부 기본값</b>인 상태로 조용히 돈다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FinchApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinchApplication.class, args);
	}

}
