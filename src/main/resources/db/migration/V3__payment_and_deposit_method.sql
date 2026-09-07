-- 충전을 ready/confirm 2단계 결제로 바꾼다 (apiSpec v0.8 §4, erd.md §2.12, GitLab 이슈 #34).
--
-- 실제 PG(카카오페이)를 쓰기로 하면서 "결제를 준비하고 결제창으로 보내는" 단계와 "돌아온 뒤 원장에
-- 반영하는" 단계가 나뉘었다. 그 사이의 상태(READY·APPROVED)를 담을 자리가 필요한데, deposit 은
-- 원장 상세라 "행이 있으면 이미 돈이 움직였다"(불변식 6)여야 하므로 중간 상태를 넣을 수 없다.
-- 그래서 상태 머신은 새 테이블 payment 가 갖고, deposit 은 확정된 것만 담는다.
--
-- deposit 의 결제수단 값도 바뀐다. VIRTUAL_CARD·VIRTUAL_TRANSFER 는 자체 모의 결제 전제의 이름이고,
-- 실제 수단은 KAKAOPAY(실API)·TRANSFER(모의 이체) 둘이다.
--
-- deposit 에 NOT NULL 컬럼을 바로 더할 수 있는 이유 — 이 테이블에 쓰는 코드가 이 MR 이전에 없었다
-- (V2 주석과 같은 근거). 어느 DB 에서도 비어 있으므로 기본값 없이 추가해도 실패하지 않는다.


-- ---------------------------------------------------------------------------
-- payment — 결제 절차 (상태 머신)
-- ---------------------------------------------------------------------------

-- 상태 전이: READY → APPROVED → DONE. 어느 단계에서든 FAILED 로 갈 수 있고, FAILED·DONE 에서는 나가지 않는다.
-- 승인만으로는 돈이 움직이지 않는다 — 원장은 DONE 으로 가는 트랜잭션(POST /deposits/confirm)에서만 기록된다.
-- 그래서 카카오 승인 콜백이 무인증이어도 안전하다 (apiSpec §4.3.1).
CREATE TABLE payment (
    -- apiSpec 4.2 의 paymentId
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id     BIGINT      NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    amount         BIGINT      NOT NULL,
    status         VARCHAR(10) NOT NULL,
    -- PG 가 발급한 키. 승인 전에는 NULL. 카카오페이는 승인 응답의 aid, 모의 이체는 서버가 만든 mock_pk_*.
    payment_key    VARCHAR(64),
    -- PG 거래 식별자 (카카오 tid). ready 응답에서 받아 approve 때 다시 보낸다.
    pg_tid         VARCHAR(64),
    fail_code      VARCHAR(50),
    created_at     TIMESTAMPTZ NOT NULL,
    approved_at    TIMESTAMPTZ,
    -- 원장 반영 시각
    completed_at   TIMESTAMPTZ,
    -- 이후 READY 인 건은 FAILED(EXPIRED) 로 정리한다 (기본 15분)
    expires_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_payment_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_payment_method  CHECK (payment_method IN ('KAKAOPAY', 'TRANSFER')),
    -- 1회 충전 한도 1천만원을 DB 가 보장한다. deposit 의 ck_deposit_amount 와 같은 값이다.
    CONSTRAINT ck_payment_amount  CHECK (amount > 0 AND amount <= 10000000),
    CONSTRAINT ck_payment_status  CHECK (status IN ('READY', 'APPROVED', 'DONE', 'FAILED')),
    -- 충전 멱등성의 근거다 (apiSpec §1.4·§4.4). 같은 키로 확정이 두 번 와도 행이 하나라 두 번째는 재생이다.
    CONSTRAINT uq_payment_key     UNIQUE (payment_key)
);

CREATE INDEX ix_payment_account ON payment (account_id, id DESC);


-- ---------------------------------------------------------------------------
-- deposit — 결제수단 값 교체, payment 와 1:1 연결
-- ---------------------------------------------------------------------------

ALTER TABLE deposit DROP CONSTRAINT ck_deposit_method;
ALTER TABLE deposit ADD CONSTRAINT ck_deposit_method CHECK (payment_method IN ('KAKAOPAY', 'TRANSFER'));

-- 어느 결제 건이 이 충전을 만들었나. UNIQUE 가 "한 결제로 두 번 충전되지 않는다"(불변식 7)를 DB 에서 보장한다.
ALTER TABLE deposit ADD COLUMN payment_id BIGINT NOT NULL;
ALTER TABLE deposit ADD CONSTRAINT fk_deposit_payment FOREIGN KEY (payment_id) REFERENCES payment (id);
ALTER TABLE deposit ADD CONSTRAINT uq_deposit_payment UNIQUE (payment_id);
