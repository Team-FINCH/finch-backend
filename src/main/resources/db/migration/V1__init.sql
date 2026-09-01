-- MVP 스키마 초기화. docs/erd/erd.md v1.0 §2 의 전사이며 설계 판단을 추가하지 않았다.
--
-- 이 파일은 한 번 적용되면 고칠 수 없다. Flyway 는 적용 시점의 파일 체크섬을 flyway_schema_history 에
-- 남기고 기동할 때마다 대조한다. 내용이 한 글자라도 바뀌면 checksum mismatch 로 기동이 거부된다.
-- 이미 적용한 사람의 로컬만 멀쩡하고 새로 clone 한 사람과 배포에서만 깨지므로 발견도 늦다.
-- 그래서 users 만 넣고 나머지를 V2 로 미루지 않고 11개 테이블을 여기서 한 번에 만든다.
-- 이후 모든 스키마 변경은 V2 이상으로만 붙인다.
--
-- 생성 순서는 FK 의존 순서다. users·stock 이 참조당하기만 하므로 먼저 오고,
-- investment_round → ledger_entry → (deposit·trade) 순으로 참조가 이어진다.
--
-- ddl-auto 는 validate 고정이다(infraSpec §3.2). validate 는 엔티티에서 출발해 대응 테이블·컬럼의
-- 존재만 검사하므로, 엔티티가 아직 없는 테이블이 10개 있어도 기동에는 영향이 없다.


-- ---------------------------------------------------------------------------
-- 확장
-- ---------------------------------------------------------------------------

-- 종목명 검색이 2글자 이상 부분 일치 자동완성이라(featureSpec 4장) B-tree 로는 접두사만 커버된다.
-- stock.stock_name 의 GIN 트라이그램 인덱스가 이 확장을 요구한다 (erd.md §2.7).
CREATE EXTENSION IF NOT EXISTS pg_trgm;


-- ---------------------------------------------------------------------------
-- 2.1 users — 회원
-- ---------------------------------------------------------------------------

-- 탈퇴가 MVP 범위 밖이라 소프트 삭제 컬럼을 두지 않는다 (featureSpec 2.1).
CREATE TABLE users (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 카카오 회원번호. 로그인마다 이 값으로 기존 회원을 찾으므로 UNIQUE 가 로그인 로직의 핵심이다.
    kakao_id          BIGINT       NOT NULL,
    nickname          VARCHAR(50)  NOT NULL,
    profile_image_url VARCHAR(500),
    -- GET /users/me 의 joinedAt
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_users_kakao_id UNIQUE (kakao_id)
);


-- ---------------------------------------------------------------------------
-- 2.7 stock — 종목 마스터
-- ---------------------------------------------------------------------------

-- 백엔드 DB 가 전종목 마스터를 소유한다 (erd.md §1.1). KIS 프록시는 앱키 호출 한도를 공유하게 되어 기각했다.
CREATE TABLE stock (
    -- 선행 0 을 보존해야 하므로 정수형을 쓰지 않는다.
    stock_code       CHAR(6)      PRIMARY KEY,
    stock_name       VARCHAR(100) NOT NULL,
    market           VARCHAR(10)  NOT NULL,
    suspended        BOOLEAN      NOT NULL DEFAULT false,
    suspended_reason VARCHAR(200),
    -- 등락 계산 기준. daily_candle 에서 유도할 수 있지만 매 요청 쓰이므로 일 1회 배치로 캐시한다.
    -- erd.md §2.7 이 "의도적 중복" 이라고 명시한 컬럼이다.
    previous_close   BIGINT,
    -- 상장폐지 시 false
    is_active        BOOLEAN      NOT NULL DEFAULT true,
    updated_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_stock_market CHECK (market IN ('KOSPI', 'KOSDAQ'))
);

CREATE INDEX ix_stock_name_trgm ON stock USING GIN (stock_name gin_trgm_ops);


-- ---------------------------------------------------------------------------
-- 2.2 investment_round — 투자 회차 (계좌)
-- ---------------------------------------------------------------------------

-- account 테이블을 따로 두지 않는다 (erd.md §1.5). users 와 1:1 이고 예수금이 회차에 귀속되므로
-- 이 테이블이 계좌 역할을 겸한다. 명세의 "가상 계좌" 는 개념어로만 남는다.
CREATE TABLE investment_round (
    id                     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                BIGINT      NOT NULL,
    -- 사용자별 1부터 증가
    round_no               INT         NOT NULL,
    status                 VARCHAR(10) NOT NULL,
    -- 예수금 스냅샷. 원장이 진실이고 이 값은 파생이며 같은 트랜잭션에서만 갱신된다 (erd.md §1.3).
    cash_balance           BIGINT      NOT NULL,
    -- 회차 누적 충전액. 회차 행에 있으므로 리셋하면 충전 한도가 자연히 초기화된다 (featureSpec 1.1).
    total_deposited_amount BIGINT      NOT NULL DEFAULT 0,
    started_at             TIMESTAMPTZ NOT NULL,
    closed_at              TIMESTAMPTZ,
    -- 종료 시점 총자산
    final_total_asset      BIGINT,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_round_user             FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_round_user_no          UNIQUE (user_id, round_no),
    CONSTRAINT ck_round_no               CHECK (round_no > 0),
    CONSTRAINT ck_round_status           CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_round_cash_balance     CHECK (cash_balance >= 0),
    CONSTRAINT ck_round_total_deposited  CHECK (total_deposited_amount BETWEEN 0 AND 100000000),
    -- 종료 회차만 closed_at·final_total_asset 을 갖는다. 둘 중 하나만 채워지는 상태를 DB 가 막는다.
    CONSTRAINT ck_round_status_columns   CHECK (
        (status = 'ACTIVE' AND closed_at IS NULL     AND final_total_asset IS NULL)
     OR (status = 'CLOSED' AND closed_at IS NOT NULL AND final_total_asset IS NOT NULL)
    )
);

-- "활성 회차는 항상 1개" (featureSpec 2.1) 를 DB 가 보장한다. 부분 유니크 인덱스라
-- CLOSED 행은 몇 개든 쌓일 수 있고 ACTIVE 만 사용자당 하나로 제한된다.
CREATE UNIQUE INDEX ux_round_active ON investment_round (user_id) WHERE status = 'ACTIVE';


-- ---------------------------------------------------------------------------
-- 2.3 ledger_entry — 원장 (불변)
-- ---------------------------------------------------------------------------

-- 잔고 변동의 단일 진실 공급원. 6종 유형을 전부 담는 불변 시계열이고 유형별 상세는
-- deposit·trade 가 1:1 로 든다 (erd.md §1.2).
--
-- 이 테이블은 불변이다. UPDATE·DELETE 를 하지 않고 정정은 반대 분개로 한다 (backConvention 6장).
-- erd.md §2.3 은 애플리케이션 DB 계정에서 이 테이블의 UPDATE·DELETE 권한을 회수해 DB 차원에서도
-- 막으라고 요구한다. 그 GRANT 회수는 운영 롤 이름에 의존하므로 이 마이그레이션 범위 밖이다.
CREATE TABLE ledger_entry (
    -- apiSpec 8.2 의 transactionId
    id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    round_id           BIGINT      NOT NULL,
    type               VARCHAR(16) NOT NULL,
    -- 예수금 증감. INITIAL_GRANT +1000000 / DEPOSIT + / BUY − / SELL + / ROUND_OPEN·ROUND_CLOSE 0
    cash_delta         BIGINT      NOT NULL,
    -- 기록 직후 예수금
    cash_balance_after BIGINT      NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ledger_round          FOREIGN KEY (round_id) REFERENCES investment_round (id),
    CONSTRAINT ck_ledger_type           CHECK (type IN (
        'INITIAL_GRANT', 'DEPOSIT', 'BUY', 'SELL', 'ROUND_OPEN', 'ROUND_CLOSE'
    )),
    CONSTRAINT ck_ledger_balance_after  CHECK (cash_balance_after >= 0)
);

-- GET /transactions 는 이 테이블 하나만 커서 페이징한다. 커서는 id 기준, 정렬은 최신순 고정이다.
CREATE INDEX ix_ledger_round_id_desc ON ledger_entry (round_id, id DESC);
CREATE INDEX ix_ledger_round_type_id ON ledger_entry (round_id, type, id DESC);


-- ---------------------------------------------------------------------------
-- 2.4 deposit — 충전 상세
-- ---------------------------------------------------------------------------

-- 충전 취소가 없으므로(featureSpec 1.1) 취소 상태 컬럼을 두지 않는다.
CREATE TABLE deposit (
    -- apiSpec 4.2 의 depositId
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_entry_id BIGINT      NOT NULL,
    -- 한도 재계산·감사용. ledger_entry 를 거치지 않고 회차별 합계를 낼 수 있다.
    round_id        BIGINT      NOT NULL,
    amount          BIGINT      NOT NULL,
    payment_method  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_deposit_ledger      FOREIGN KEY (ledger_entry_id) REFERENCES ledger_entry (id),
    CONSTRAINT fk_deposit_round       FOREIGN KEY (round_id) REFERENCES investment_round (id),
    -- 원장 1행 : 상세 1행. UNIQUE 가 erd.md §4 불변식 6 의 절반을 DB 에서 보장한다.
    CONSTRAINT uq_deposit_ledger      UNIQUE (ledger_entry_id),
    -- 1회 충전 한도 1천만원을 DB 가 보장한다.
    CONSTRAINT ck_deposit_amount      CHECK (amount > 0 AND amount <= 10000000),
    CONSTRAINT ck_deposit_method      CHECK (payment_method IN ('VIRTUAL_CARD', 'VIRTUAL_TRANSFER'))
);

CREATE INDEX ix_deposit_round ON deposit (round_id);


-- ---------------------------------------------------------------------------
-- 2.5 trade — 체결 상세
-- ---------------------------------------------------------------------------

-- MVP 는 시장가 즉시 체결이라 접수와 체결이 분리되지 않는다. 주문 1건 = 이 테이블 1행이고
-- id 가 orderId(apiSpec 7.1) 이자 tradeId(9.2) 다. 별도 order 테이블을 두지 않는다 (erd.md §2.5).
-- 지정가가 들어오면 order 를 신설하고 trade 를 그 하위로 내린다.
CREATE TABLE trade (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_entry_id BIGINT      NOT NULL,
    round_id        BIGINT      NOT NULL,
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
    CONSTRAINT fk_trade_round           FOREIGN KEY (round_id) REFERENCES investment_round (id),
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

CREATE INDEX ix_trade_round_id_desc ON trade (round_id, id DESC);
CREATE INDEX ix_trade_round_stock   ON trade (round_id, stock_code, id DESC);


-- ---------------------------------------------------------------------------
-- 2.6 holding — 보유 종목 (회차별)
-- ---------------------------------------------------------------------------

-- round_id 에 묶여 있으므로 과거 회차의 보유 종목이 그대로 남아 읽기 전용 스냅샷이 된다
-- (featureSpec 2.3). 별도 스냅샷 테이블이 필요 없다.
CREATE TABLE holding (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    round_id      BIGINT      NOT NULL,
    stock_code    CHAR(6)     NOT NULL,
    quantity      BIGINT      NOT NULL,
    -- 가중평균 매입 단가
    avg_buy_price BIGINT      NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_holding_round      FOREIGN KEY (round_id) REFERENCES investment_round (id),
    CONSTRAINT fk_holding_stock      FOREIGN KEY (stock_code) REFERENCES stock (stock_code),
    CONSTRAINT uq_holding_round_stock UNIQUE (round_id, stock_code),
    CONSTRAINT ck_holding_quantity   CHECK (quantity >= 0),
    CONSTRAINT ck_holding_avg_price  CHECK (avg_buy_price >= 0),
    -- 전량 매도 시 행을 지우지 않고 quantity = 0, avg_buy_price = 0 으로 남긴다.
    -- 재매수 때 INSERT 경합 없이 같은 행을 갱신하기 위해서다.
    CONSTRAINT ck_holding_zeroed     CHECK (quantity > 0 OR avg_buy_price = 0)
);

-- 조회는 quantity > 0 으로 거른다. featureSpec 7.3 의 "잔고에서 제거" 는 화면 기준으로 해석했다.
CREATE INDEX ix_holding_round_held ON holding (round_id) WHERE quantity > 0;


-- ---------------------------------------------------------------------------
-- 2.8 daily_candle — 일봉
-- ---------------------------------------------------------------------------

-- PK 가 곧 period=1M|3M|1Y range scan 의 인덱스다. 분봉 도입 시(S0-4) 이 테이블을 건드리지 않고
-- minute_candle 을 새로 만든다.
CREATE TABLE daily_candle (
    stock_code  CHAR(6) NOT NULL,
    trade_date  DATE    NOT NULL,
    open_price  BIGINT  NOT NULL,
    high_price  BIGINT  NOT NULL,
    low_price   BIGINT  NOT NULL,
    close_price BIGINT  NOT NULL,
    volume      BIGINT  NOT NULL,

    PRIMARY KEY (stock_code, trade_date),
    CONSTRAINT fk_candle_stock      FOREIGN KEY (stock_code) REFERENCES stock (stock_code),
    CONSTRAINT ck_candle_prices     CHECK (
        open_price > 0 AND high_price > 0 AND low_price > 0 AND close_price > 0
    ),
    CONSTRAINT ck_candle_volume     CHECK (volume >= 0),
    CONSTRAINT ck_candle_high_low   CHECK (
        high_price >= low_price AND high_price >= open_price AND high_price >= close_price
        AND low_price <= open_price AND low_price <= close_price
    )
);


-- ---------------------------------------------------------------------------
-- 2.9 watchlist_item — 관심 종목
-- ---------------------------------------------------------------------------

-- 최대 50개는 DB 제약으로 표현할 수 없어 애플리케이션이 검증한다 (WATCHLIST_LIMIT_EXCEEDED).
-- 리셋해도 유지된다 — user_id 에 묶여 있고 round_id 가 없는 것이 그 결정의 표현이다 (erd.md §7).
CREATE TABLE watchlist_item (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    stock_code CHAR(6)     NOT NULL,
    -- 응답의 registeredAt
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_watchlist_user       FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_watchlist_stock      FOREIGN KEY (stock_code) REFERENCES stock (stock_code),
    -- WATCHLIST_ALREADY_EXISTS 를 DB 가 판정한다.
    CONSTRAINT uq_watchlist_user_stock UNIQUE (user_id, stock_code)
);

-- sort=REGISTERED 가 이 인덱스로 처리된다. sort=NAME 은 stock 과 조인해 DB 에서 정렬하고,
-- sort=CHANGE_RATE 는 기준 값이 Redis 시세 캐시에 있어 애플리케이션에서 정렬한다.
CREATE INDEX ix_watchlist_user ON watchlist_item (user_id, created_at DESC);


-- ---------------------------------------------------------------------------
-- 2.10 recent_viewed_stock — 최근 본 종목
-- ---------------------------------------------------------------------------

-- 별도 등록 API 가 없고 GET /stocks/{stockCode} 처리 중 UPSERT 한다 (apiSpec 5.2).
-- 30건 FIFO 상한은 UPSERT 후 초과분을 삭제해 유지한다.
CREATE TABLE recent_viewed_stock (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    stock_code CHAR(6)     NOT NULL,
    viewed_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_recent_viewed_user  FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_recent_viewed_stock FOREIGN KEY (stock_code) REFERENCES stock (stock_code),
    -- "중복 없이 최상단 갱신" 을 보장한다. UPSERT 의 충돌 대상이기도 하다.
    CONSTRAINT uq_recent_viewed_user_stock UNIQUE (user_id, stock_code)
);

CREATE INDEX ix_recent_viewed_user ON recent_viewed_stock (user_id, viewed_at DESC);


-- ---------------------------------------------------------------------------
-- 2.11 recent_search_keyword — 최근 검색어
-- ---------------------------------------------------------------------------

-- 최대 10건. 같은 검색어를 다시 검색하면 searched_at 만 갱신한다.
CREATE TABLE recent_search_keyword (
    -- DELETE /stocks/search/recent/{keywordId} 의 그 값
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    keyword     VARCHAR(50) NOT NULL,
    searched_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_recent_keyword_user     FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_recent_keyword_user_kw  UNIQUE (user_id, keyword)
);

CREATE INDEX ix_recent_keyword_user ON recent_search_keyword (user_id, searched_at DESC);
