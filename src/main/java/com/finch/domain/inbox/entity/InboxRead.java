package com.finch.domain.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * `inbox_read` 테이블 (erd.md §2.14) — 알림함 항목의 읽음 표시. <b>알림함 항목 자체는 저장하지 않는다</b> — 조회 때마다 계산하고
 * ({@code InboxService} 주석) 여기에는 사용자가 무엇을 읽었는지만 남는다.
 * <p>
 * 쓰기는 {@code InboxReadRepository.markRead} 의 네이티브 {@code INSERT ... ON CONFLICT DO NOTHING} 하나다. 엔티티는
 * {@code ddl-auto: validate} 가 스키마를 대조하고 리포지토리가 설 자리를 주려고 있다.
 */
@Entity
@Table(name = "inbox_read")
@IdClass(InboxRead.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxRead {

	@Id
	@Column(nullable = false, updatable = false)
	private Long userId;

	/** 서버가 만든 불투명 식별자 (지금은 {@code record-{종목코드}-{tradeId}}). 64자 상한은 apiSpec 6.4. */
	@Id
	@Column(length = 64, nullable = false, updatable = false)
	private String itemId;

	@Column(nullable = false, updatable = false)
	private Instant readAt;

	/** 복합 PK. JPA 가 요구하는 모양이라 기본 생성자와 {@code equals}/{@code hashCode} 가 있어야 한다. */
	@Getter
	@EqualsAndHashCode
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class Key implements Serializable {

		private Long userId;
		private String itemId;
	}
}
