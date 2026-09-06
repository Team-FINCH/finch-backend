package com.finch.domain.deposit.exception;

import com.finch.global.exception.CustomException;

/**
 * "그 건은 {@code FAILED} 로 굳는다"를 동반하는 거절이다 (apiSpec 4.3.2·4.4 — 금액 불일치, 누적 한도 초과,
 * 모의 승인 실패 시나리오).
 * <p>
 * 보통의 {@code CustomException} 과 다른 점 하나 — <b>던져도 트랜잭션이 롤백되면 안 된다.</b> 거절 직전에
 * {@code payment.fail()} 로 바꾼 상태가 커밋되어야 다음 confirm 이 "실패로 굳은 건" 409 를 받는다.
 * 롤백되면 그 건이 APPROVED 로 되돌아가 사용자가 같은 건을 다시 시도할 수 있고, 그것은 featureSpec 3.3
 * "실패로 굳는다"에 어긋난다. 그래서 서비스가 {@code @Transactional(noRollbackFor = 이 타입)} 으로 잡는다.
 * <p>
 * 타입을 따로 둔 이유 — {@code noRollbackFor = CustomException.class} 로 두면 앞으로 그 메서드에 추가되는
 * 어떤 거절도 롤백되지 않는다. "쓰고 나서 거절" 은 이 타입으로만 표현하고, 나머지 거절은 쓰기 전에 일어난다.
 * <p>
 * {@code GlobalExceptionHandler} 는 {@code CustomException} 핸들러로 이 타입도 받는다. 응답 모양은 같다.
 */
public class DepositRejectedException extends CustomException {

	public DepositRejectedException(DepositErrorCode errorCode) {
		super(errorCode);
	}

	public DepositRejectedException(DepositErrorCode errorCode, Object detail) {
		super(errorCode, detail);
	}
}
