package com.finch.global.security;

import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.global.exception.CustomException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link LoginUser} 가 붙은 파라미터를 SecurityContext 의 principal 로 채운다.
 * <p>
 * 필터와의 순서 관계 — 필터는 서블릿 계층이라 요청이 <b>들어올 때</b> 돌고, 이 리졸버는
 * DispatcherServlet 이 핸들러 메서드의 인자를 조립할 <b>직전</b>에 돈다. 즉 이 코드가 실행되는 시점에는
 * 필터가 이미 끝나 있고 SecurityContext 는 채워져 있다. 순서가 보장되므로 여기서 토큰을 다시 읽지 않는다.
 * <p>
 * {@code @AuthenticationPrincipal} 을 쓰지 않은 이유 — 그 어노테이션은 principal 객체를 그대로 주므로
 * 컨트롤러가 principal 의 타입을 알아야 한다. principal 을 나중에 {@code UserDetails} 로 바꾸면
 * 그것을 쓰는 컨트롤러 전부가 같이 바뀐다. {@code @LoginUser Long} 은 그 타입을 이 클래스 안에 가둔다.
 */
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

	/**
	 * 타입까지 조건에 넣지 않는다. {@code false} 를 주면 스프링이 그 파라미터를 <b>모델 애트리뷰트로
	 * 조용히 바인딩</b>해서 {@code @LoginUser String} 같은 오타가 null 로 넘어간다.
	 * 여기서 받고 아래에서 큰 소리로 실패하는 쪽이 낫다.
	 */
	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(LoginUser.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Class<?> type = parameter.getParameterType();
		if (type != Long.class && type != long.class) {
			// 개발 시점의 실수다. 사용자에게 보일 상황이 아니므로 에러 코드를 만들지 않고 즉시 터뜨린다.
			throw new IllegalStateException(
				"@LoginUser 는 Long 파라미터에만 붙인다. 실제 타입: " + type.getName());
		}

		return userIdOf(SecurityContextHolder.getContext().getAuthentication());
	}

	/**
	 * 정상 경로에서는 항상 성공한다 — {@code authenticated()} 를 통과했다면 principal 이 있다.
	 * <p>
	 * 그럼에도 검사하는 이유는 <b>화이트리스트 경로에 {@code @LoginUser} 를 붙인 경우</b>다.
	 * 그때 principal 은 없거나 익명 사용자의 문자열이고, 검사가 없으면 컨트롤러가 null 을 받아
	 * 한참 뒤 엉뚱한 자리에서 NPE 가 난다. 여기서 던지면 DispatcherServlet 안이라
	 * {@code GlobalExceptionHandler} 가 받아 정상적인 401 로 나간다.
	 */
	private static Long userIdOf(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
			throw new CustomException(AuthErrorCode.AUTH_INVALID_TOKEN);
		}
		return userId;
	}
}
