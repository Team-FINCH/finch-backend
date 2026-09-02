package com.finch.global.config;

import com.finch.global.security.LoginUserArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 확장점. 지금은 {@code @LoginUser} 리졸버 등록 하나뿐이다.
 * <p>
 * {@code @EnableWebMvc} 를 붙이지 않는다. 그걸 붙이면 Boot 의 MVC 자동 구성이 통째로 꺼져서
 * 메시지 컨버터·에러 처리·정적 리소스 설정을 전부 직접 다시 해야 한다.
 * {@code WebMvcConfigurer} 구현만으로 자동 구성 위에 얹힌다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	/**
	 * 커스텀 리졸버는 스프링 기본 리졸버들 <b>뒤</b>에 붙는다. {@code @LoginUser} 는 스프링이 아는
	 * 어노테이션이 아니므로 앞에서 가로채는 것이 없어 순서가 문제되지 않는다.
	 */
	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new LoginUserArgumentResolver());
	}
}
