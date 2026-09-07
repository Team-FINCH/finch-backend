package com.finch.domain.deposit.service;

import com.finch.domain.deposit.repository.PaymentRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제창으로 갔다가 돌아오지 않은 READY 건을 {@code expires_at} 뒤에 FAILED(EXPIRED) 로 정리한다 (apiSpec 4.2).
 * <p>
 * 하루 1회면 충분하다 — 만료된 READY 건은 confirm 이 어차피 "승인 전" 409 로 막고, 승인 콜백도 잠근 뒤 READY 를
 * 다시 확인하므로 정리가 늦어도 돈이 움직이지 않는다. 이 배치는 정합성이 아니라 <b>정리</b>다. 새벽에 도는 것은
 * 시연 시간대와 겹치지 않게 하기 위해서다.
 * <p>
 * 두 인스턴스가 같은 시각에 돌아도 같은 UPDATE 를 두 번 할 뿐이라 리더 락이 필요 없다. 리더 락은 KIS 워커(S10)의 몫이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpiryScheduler {

	private final PaymentRepository paymentRepository;

	@Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
	@Transactional
	public void expireAbandoned() {
		expireBefore(Instant.now());
	}

	/** 기준 시각을 받는 이유는 테스트다 — 15분을 기다리지 않고 미래 시각을 넣어 만료를 일으킨다. */
	@Transactional
	public int expireBefore(Instant now) {
		int expired = paymentRepository.expireReadyBefore(now);
		if (expired > 0) {
			log.info("만료된 결제 준비 건 정리 count={}", expired);
		}
		return expired;
	}
}
