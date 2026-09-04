package com.finch.domain.auth.service;

import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 한 건을 <b>한 트랜잭션</b>으로 묶는다 — `users` INSERT → `account` INSERT → `ledger_entry`
 * INSERT (erd.md §3.1).
 * <p>
 * <b>{@code AuthService} 에서 떼어낸 이유.</b> 그 클래스는 카카오 HTTP 호출 때문에 트랜잭션이 없어야
 * 한다(그 클래스 주석의 두 이유 — 커넥션 점유, 그리고 동시 가입 경합을 잡으려면 재조회가 새
 * 트랜잭션으로 나가야 한다). 그런데 계좌·원장 생성은 반드시 트랜잭션이 필요하다. 그래서 흐름을
 * <b>"카카오 확인(트랜잭션 밖) → 등록(트랜잭션 안)"</b> 두 단계로 나누고, 트랜잭션이 필요한 쪽만
 * 별도 빈으로 뺐다. 같은 클래스에 두면 자기 호출이라 프록시를 지나지 않아 {@code @Transactional} 이
 * 아예 걸리지 않는다.
 * <p>
 * <b>{@code account} 를 참조하는 것에 대하여.</b> backConvention 2.4 규칙 2 는 {@code auth} 와
 * {@code account} 를 같은 2층에 두고 같은 층 참조를 금지한다. 그 규칙의 근거는 "순환을 만들 수 있어서"
 * 인데, {@code account} 는 {@code auth} 를 참조하지 않으므로 여기엔 순환이 없다. 가입 시 계좌 개설은
 * 두 도메인을 한 트랜잭션으로 묶는 것이 요구사항이라(erd.md §3.1) 이벤트로 끊어도 의존은 남는다.
 * 그래서 <b>backConvention 2.4 에 이 한 쌍을 예외로 명문화</b>하고 직접 부른다.
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationService {

	private final UserRepository userRepository;
	private final AccountService accountService;

	/**
	 * 신규 사용자를 만들고 계좌와 초기 예수금까지 같은 트랜잭션에서 만든다.
	 * <p>
	 * 여기서 {@code DataIntegrityViolationException} 을 <b>잡지 않는다.</b> 동시 가입 경합
	 * ({@code uq_users_kakao_id} 위반)의 처리는 호출자인 {@code AuthService} 의 몫이다 — 제약 위반이 난
	 * 트랜잭션은 rollback-only 로 표시되므로 <b>같은 트랜잭션 안에서 잡아 재조회해도 커밋에서 다시
	 * 실패한다.</b> 트랜잭션 밖에서 잡아야 재조회가 새 트랜잭션으로 나간다.
	 * <p>
	 * 그래서 이 메서드는 실패를 그대로 위로 던진다. 실패하면 세 INSERT 가 함께 롤백되어
	 * <b>계정만 있고 계좌가 없는 상태</b>가 생기지 않는다.
	 */
	@Transactional
	public User register(KakaoUser kakaoUser) {
		User user = userRepository.save(
			User.register(kakaoUser.kakaoId(), kakaoUser.nickname(), kakaoUser.profileImageUrl()));

		accountService.openAccount(user.getId());

		return user;
	}
}
