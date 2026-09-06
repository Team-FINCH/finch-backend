package com.finch.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 를 켠다. 첫 사용처는 만료된 결제 준비 건 정리({@code PaymentExpiryScheduler})이고
 * KIS 폴링 워커(S10)·일봉 배치가 뒤따른다.
 * <p>
 * 도메인이 아니라 여기 두는 이유 — 스케줄링은 애플리케이션 전체에 한 번만 켜는 스위치라 어느 도메인의 것도 아니다.
 * 기본 스케줄러는 스레드 하나다. 작업이 겹치기 시작하면 {@code spring.task.scheduling.pool.size} 를 올린다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
