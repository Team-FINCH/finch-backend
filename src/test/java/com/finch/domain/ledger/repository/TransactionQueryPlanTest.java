package com.finch.domain.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * `GET /transactions` 의 두 쿼리가 erd.md §2.3 의 인덱스를 타는지 {@code EXPLAIN} 으로 본다 (backend_story S4 완료 조건).
 * <p>
 * 빈 테이블에서는 플래너가 언제나 Seq Scan 을 고르므로 {@code enable_seqscan = off} 로 두고 본다 — 그러면 "인덱스가
 * 쓸 수 있는 모양인가"가 드러난다. 조건 컬럼 순서(account_id → type → id DESC)가 인덱스 정의와 어긋나면 여기서 잡힌다.
 * 정렬 노드의 유무는 보지 않는다 — 행 추정치가 1~2 인 빈 테이블에서는 플래너가 인덱스 순서를 믿는 대신 값싼 Sort 를
 * 얹는다. 실제 데이터 양에서의 플래너 선택은 운영 DB 에서 다시 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionQueryPlanTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	@DisplayName("유형 필터 쿼리는 ix_ledger_account_type_id (account_id, type, id DESC) 를 탄다")
	void typeFilterUsesTypeIndex() {
		String plan = explain(TransactionQueryRepository.SELECT + """
			 WHERE a.user_id = 1 AND le.type = 'DEPOSIT' AND le.id < 9223372036854775807
			 ORDER BY le.id DESC LIMIT 31
			""");

		System.out.println("[EXPLAIN type=DEPOSIT]\n" + plan);
		assertThat(plan).contains("ix_ledger_account_type_id");
	}

	@Test
	@DisplayName("ALL 쿼리는 ix_ledger_account_id_desc (account_id, id DESC) 를 탄다")
	void allUsesAccountIndex() {
		String plan = explain(TransactionQueryRepository.SELECT + """
			 WHERE a.user_id = 1 AND le.id < 9223372036854775807
			 ORDER BY le.id DESC LIMIT 31
			""");

		System.out.println("[EXPLAIN type=ALL]\n" + plan);
		assertThat(plan).contains("ix_ledger_account_id_desc");
	}

	/** SET 과 EXPLAIN 이 같은 커넥션에서 돌아야 하므로 트랜잭션 하나로 묶는다 ({@code SET LOCAL} 은 트랜잭션 끝에 풀린다). */
	private String explain(String sql) {
		return transactionTemplate.execute(status -> {
			jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
			List<String> lines = jdbcTemplate.queryForList("EXPLAIN " + sql, String.class);
			return String.join("\n", lines);
		});
	}
}
