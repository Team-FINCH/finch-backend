-- 예수금 출금을 추가한다 (apiSpec v0.8 §4.5, erd.md §2.13 §3.4, GitLab 이슈 #15).
--
-- 출금은 2026-09-04 에 새로 정한 기능이다. 충전 취소(MVP 제외)와 다르다 — 취소는 특정 충전 건을 되돌리는
-- 것이라 그 돈이 이후 매매에 쓰였는지 건별로 추적해야 하고, 출금은 지금 잔고에서 빼는 별개 사건이라 추적이
-- 없다 (featureSpec 3.4). 그래서 deposit·payment 를 건드리지 않고 원장 유형 하나와 상세 테이블 하나만 더한다.
--
-- ledger_entry 의 CHECK 를 교체하는 것 외에는 기존 테이블에 손대지 않는다. 특히 account.total_deposited_amount
-- 는 그대로다 — 출금은 충전 한도를 되돌리지 않는다 (erd.md §2.2). 되돌리면 충전↔출금 반복으로 누적 한도를
-- 무한히 우회할 수 있고 불변식 2(누적 충전액 = SUM(deposit.amount))도 깨진다.


-- ---------------------------------------------------------------------------
-- ledger_entry — 원장 유형에 WITHDRAWAL 추가
-- ---------------------------------------------------------------------------

-- 유형이 4종에서 5종이 된다. 기록 주체는 withdrawal 도메인이고 cash_delta 는 음수다 (backConvention 2.5).
-- CHECK 는 교체만 한다 — 기존 행은 전부 목록 안의 값이라 재검증에 실패하지 않는다.
ALTER TABLE ledger_entry DROP CONSTRAINT ck_ledger_type;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_type
    CHECK (type IN ('INITIAL_GRANT', 'DEPOSIT', 'WITHDRAWAL', 'BUY', 'SELL'));


-- ---------------------------------------------------------------------------
-- withdrawal — 출금 상세
-- ---------------------------------------------------------------------------

-- deposit 과 같은 모양이다 — 원장 1행에 상세 1행이 붙는다 (불변식 6).
-- payment 같은 상태 머신이 없다. 출금은 외부 PG 를 거치지 않아 중간 상태가 생기지 않고, 요청 한 번이 곧
-- 확정이다. 재전송은 Idempotency-Key 헤더(global/idempotency 필터)가 막으므로 이 테이블에 키 컬럼도 없다.
-- 출금 수단(은행·계좌번호) 컬럼도 없다 — 모의 서비스라 받아도 쓰이는 곳이 없다 (featureSpec 3.4).
CREATE TABLE withdrawal (
    -- apiSpec 4.5 의 withdrawalId
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_entry_id BIGINT      NOT NULL,
    -- 조회·감사용. ledger_entry 를 거치지 않고 계좌별 합계를 낼 수 있다.
    account_id      BIGINT      NOT NULL,
    -- 양수 절대값. 부호는 원장(cash_delta)만 갖는다 — deposit 과 같은 규칙이라 내역 화면이 두 유형을
    -- 같은 방식으로 다룰 수 있다.
    amount          BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_withdrawal_ledger  FOREIGN KEY (ledger_entry_id) REFERENCES ledger_entry (id),
    CONSTRAINT fk_withdrawal_account FOREIGN KEY (account_id) REFERENCES account (id),
    -- 원장 1행 : 상세 1행. UNIQUE 가 불변식 6 의 절반을 DB 에서 보장한다.
    CONSTRAINT uq_withdrawal_ledger  UNIQUE (ledger_entry_id),
    CONSTRAINT ck_withdrawal_amount  CHECK (amount > 0)
);

CREATE INDEX ix_withdrawal_account ON withdrawal (account_id);
