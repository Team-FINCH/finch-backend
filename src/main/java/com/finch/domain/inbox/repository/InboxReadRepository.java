package com.finch.domain.inbox.repository;

import com.finch.domain.inbox.entity.InboxRead;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** `inbox_read` 는 inbox 도메인 소유다 (backConvention 2.2). */
public interface InboxReadRepository extends JpaRepository<InboxRead, InboxRead.Key> {

	/**
	 * 읽음 표시. <b>이미 있으면 아무것도 하지 않는다</b> — apiSpec 6.4 의 "멱등, 언제나 204" 가 이 한 줄이다. 조회 후 저장으로 나누면
	 * 같은 항목을 두 탭에서 동시에 열었을 때 PK 충돌이 500 으로 나간다.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
		INSERT INTO inbox_read (user_id, item_id, read_at)
		VALUES (:userId, :itemId, :readAt)
		ON CONFLICT (user_id, item_id) DO NOTHING
		""")
	void markRead(Long userId, String itemId, Instant readAt);

	/** 이 중 읽은 것만. 목록에 나갈 항목 id 로만 묻는다 — 지난 항목의 읽음 행까지 끌어오지 않는다. */
	@Query(nativeQuery = true, value = """
		SELECT r.item_id
		  FROM inbox_read r
		 WHERE r.user_id = :userId
		   AND r.item_id IN (:itemIds)
		""")
	List<String> findReadItemIds(Long userId, Collection<String> itemIds);
}
