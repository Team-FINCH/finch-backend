-- 투자 회차와 계좌 리셋을 제거하고 investment_round 를 account 로 교체한다 (GitLab 이슈 #27).
--
-- 실제 투자 서비스에는 계좌를 초기화하고 다시 시작하는 기능이 없다. 투자 회차는 독립된 기능이 아니라
-- 리셋의 부산물이었고(featureSpec §0 "계좌 리셋 시점을 경계로 구분되는 원장 단위"), 리셋이 없어지면
-- 회차를 나눌 경계도 없어진다.
--
-- investment_round 는 회차 테이블이 아니라 계좌 테이블 그 자체였다 (erd.md §1.5 — "account 테이블을
-- 따로 두지 않는다"). 예수금과 누적 충전액이 이 테이블에 있고 ledger_entry·deposit·trade·holding 이
-- round_id 로 매달려 있으므로, 회차 제거는 테이블 삭제가 아니라 account 로의 교체다.
--
-- ALTER 를 쌓지 않고 DROP 후 다시 만든다. 다섯 테이블에 쓰는 코드가 아직 없어(엔티티는 User 하나뿐,
-- AuthService 주석 참고) 어느 DB 에서도 비어 있는 것이 코드로 보장되기 때문이다. 컬럼·제약·인덱스가
-- 전부 바뀌는 마당에 ALTER 체인은 길기만 하고 결과 스키마를 읽을 수 없게 만든다.
--
-- users·stock·daily_candle·watchlist_item·recent_viewed_stock·recent_search_keyword 는 손대지 않는다.
-- users 에는 카카오 로그인 검증 행이 있고 그대로 보존된다.


-- ---------------------------------------------------------------------------
-- 기존 회차 계열 테이블 제거 — FK 참조의 역순
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS holding;
DROP TABLE IF EXISTS trade;
DROP TABLE IF EXISTS deposit;
DROP TABLE IF EXISTS ledger_entry;
DROP TABLE IF EXISTS investment_round;


-- ---------------------------------------------------------------------------
-- account — 계좌 (users 와 1:1)
-- ---------------------------------------------------------------------------

-- round_no·status·closed_at·final_total_asset 이 사라진다. 회차 번호는 나눌 경계가 없어 무의미하고,
-- status 는 ACTIVE 만 남아 상수가 되며, 나머지 둘은 회차 종료 시점에만 쓰이던 컬럼이다.
-- ux_round_active(활성 회차 1개)의 자리는 user_id UNIQUE 가 대신한다 — 1:N 이 1:1 이 되었다.
CREATE TABLE account (
    id                     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                BIGINT      NOT NULL,
    -- 예수금 스냅샷. 원장이 진실이고 이 값은 파생이며 같은 트랜잭션에서만 갱신된다 (erd.md §1.3).
    cash_balance           BIGINT      NOT NULL,
    -- 계정 전체 누적 충전액. 회차가 없어졌으므로 한도의 기준 기간도 회차가 아니라 계좌 평생이다.
    total_deposited_amount BIGINT      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_account_user            FOREIGN KEY (user_id) REFERENCES users (id),
    -- 계정 1개 : 계좌 1개. 리셋이 없으므로 한 사용자가 계좌를 둘 가질 경로가 없다.
    CONSTRAINT uq_account_user            UNIQUE (user_id),
    CONSTRAINT ck_account_cash_balance    CHECK (cash_balance >= 0),
    CONSTRAINT ck_account_total_deposited CHECK (total_deposited_amount BETWEEN 0 AND 100000000)
);


-- ---------------------------------------------------------------------------
-- ledger_entry — 원장 (불변)
-- ---------------------------------------------------------------------------

-- 유형이 6종에서 4종으로 줄어든다. ROUND_OPEN·ROUND_CLOSE 는 회차 전환을 기록하던 delta 0 행이라
-- 회차가 없어지면 기록할 사건 자체가 없다.
--
-- 이 테이블은 불변이다. UPDATE·DELETE 를 하지 않고 정정은 반대 분개로 한다 (backConvention 6장).
CREATE TABLE ledger_entry (
    -- apiSpec 8.2 의 transactionId
    id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id         BIGINT      NOT NULL,
    type               VARCHAR(16) NOT NULL,
    -- 예수금 증감. INITIAL_GRANT +1000000 / DEPOSIT + / BUY - / SELL +
    cash_delta         BIGINT      NOT NULL,
    -- 기록 직후 예수금
    cash_balance_after BIGINT      NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ledger_account        FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_ledger_type           CHECK (type IN ('INITIAL_GRANT', 'DEPOSIT', 'BUY', 'SELL')),
    CONSTRAINT ck_ledger_balance_after  CHECK (cash_balance_after >= 0)
);

-- GET /transactions 는 이 테이블 하나만 커서 페이징한다. 커서는 id 기준, 정렬은 최신순 고정이다.
CREATE INDEX ix_ledger_account_id_desc ON ledger_entry (account_id, id DESC);
CREATE INDEX ix_ledger_account_type_id ON ledger_entry (account_id, type, id DESC);


-- ---------------------------------------------------------------------------
-- deposit — 충전 상세
-- ---------------------------------------------------------------------------

-- 충전 취소가 없으므로(featureSpec 1.1) 취소 상태 컬럼을 두지 않는다.
CREATE TABLE deposit (
    -- apiSpec 4.2 의 depositId
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_entry_id BIGINT      NOT NULL,
    -- 한도 재계산·감사용. ledger_entry 를 거치지 않고 계좌별 합계를 낼 수 있다.
    account_id      BIGINT      NOT NULL,
    amount          BIGINT      NOT NULL,
    payment_method  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_deposit_ledger      FOREIGN KEY (ledger_entry_id) REFERENCES ledger_entry (id),
    CONSTRAINT fk_deposit_account     FOREIGN KEY (account_id) REFERENCES account (id),
    -- 원장 1행 : 상세 1행. UNIQUE 가 erd.md §4 불변식 6 의 절반을 DB 에서 보장한다.
    CONSTRAINT uq_deposit_ledger      UNIQUE (ledger_entry_id),
    -- 1회 충전 한도 1천만원을 DB 가 보장한다.
    CONSTRAINT ck_deposit_amount      CHECK (amount > 0 AND amount <= 10000000),
    CONSTRAINT ck_deposit_method      CHECK (payment_method IN ('VIRTUAL_CARD', 'VIRTUAL_TRANSFER'))
);

CREATE INDEX ix_deposit_account ON deposit (account_id);


-- ---------------------------------------------------------------------------
-- trade — 체결 상세
-- ---------------------------------------------------------------------------

-- MVP 는 시장가 즉시 체결이라 접수와 체결이 분리되지 않는다. 주문 1건 = 이 테이블 1행이고
-- id 가 orderId(apiSpec 7.1) 이자 tradeId(9.2) 다. 별도 order 테이블을 두지 않는다 (erd.md §2.5).
CREATE TABLE trade (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_entry_id BIGINT      NOT NULL,
    account_id      BIGINT      NOT NULL,
    stock_code      CHAR(6)     NOT NULL,
    side            VARCHAR(4)  NOT NULL,
    quantity        BIGINT      NOT NULL,
    -- 체결 시점 최신 수신 가격
    executed_price  BIGINT      NOT NULL,
    executed_amount BIGINT      NOT NULL,
    -- 매도 시 체결 직전 평균 매수가 스냅샷. 평단은 매도 후 바뀌므로 이 컬럼이 없으면
    -- 과거 수익률을 재현할 수 없다. realizedProfitRate 는 저장하지 않고 여기서 계산한다.
    avg_buy_price   BIGINT,
    realized_profit BIGINT,
    executed_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_trade_ledger          FOREIGN KEY (ledger_entry_id) REFERENCES ledger_entry (id),
    CONSTRAINT fk_trade_account         FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_trade_stock           FOREIGN KEY (stock_code) REFERENCES stock (stock_code),
    CONSTRAINT uq_trade_ledger          UNIQUE (ledger_entry_id),
    CONSTRAINT ck_trade_side            CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_trade_quantity        CHECK (quantity > 0),
    CONSTRAINT ck_trade_executed_price  CHECK (executed_price > 0),
    CONSTRAINT ck_trade_executed_amount CHECK (executed_amount = quantity * executed_price),
    -- 매도만 평단 스냅샷과 실현손익을 갖는다.
    CONSTRAINT ck_trade_side_columns    CHECK (
        (side = 'SELL' AND avg_buy_price IS NOT NULL AND realized_profit IS NOT NULL)
     OR (side = 'BUY'  AND avg_buy_price IS NULL     AND realized_profit IS NULL)
    )
);

CREATE INDEX ix_trade_account_id_desc ON trade (account_id, id DESC);
CREATE INDEX ix_trade_account_stock   ON trade (account_id, stock_code, id DESC);


-- ---------------------------------------------------------------------------
-- holding — 보유 종목
-- ---------------------------------------------------------------------------

-- 회차별 스냅샷이라는 성격이 사라진다. 계좌당 종목 1행이고 그 행이 현재 잔고 그 자체다.
-- 과거의 보유 이력은 trade 로만 남는다.
CREATE TABLE holding (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT      NOT NULL,
    stock_code    CHAR(6)     NOT NULL,
    quantity      BIGINT      NOT NULL,
    -- 가중평균 매입 단가
    avg_buy_price BIGINT      NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_holding_account       FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_holding_stock         FOREIGN KEY (stock_code) REFERENCES stock (stock_code),
    CONSTRAINT uq_holding_account_stock UNIQUE (account_id, stock_code),
    CONSTRAINT ck_holding_quantity      CHECK (quantity >= 0),
    CONSTRAINT ck_holding_avg_price     CHECK (avg_buy_price >= 0),
    -- 전량 매도 시 행을 지우지 않고 quantity = 0, avg_buy_price = 0 으로 남긴다.
    -- 재매수 때 INSERT 경합 없이 같은 행을 갱신하기 위해서다.
    CONSTRAINT ck_holding_zeroed        CHECK (quantity > 0 OR avg_buy_price = 0)
);

-- 조회는 quantity > 0 으로 거른다. featureSpec 7.3 의 "잔고에서 제거" 는 화면 기준으로 해석했다.
CREATE INDEX ix_holding_account_held ON holding (account_id) WHERE quantity > 0;
