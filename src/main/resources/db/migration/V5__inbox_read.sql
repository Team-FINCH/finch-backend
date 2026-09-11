-- 알림함 읽음 표시를 추가한다 (apiSpec v0.8.9 §6.4, erd.md §2.14, GitLab 이슈 #57).
--
-- 알림함 항목 자체는 저장하지 않는다. "적어야 할 것"(record)은 보유 종목과 위키 논지를 대조해 조회 때마다
-- 계산한다 — 논지가 생기면 별도 처리 없이 목록에서 빠지게 하려는 것이다. 그래서 여기 남는 것은 사용자가
-- 어느 항목을 읽었는지 하나뿐이다.
--
-- Redis 가 아니라 DB 에 두는 이유 — 읽음은 사용자 상태라 날아가면 뱃지가 되살아난다. erd.md §1.4 는 휘발성
-- 데이터만 Redis 에 둔다.


-- ---------------------------------------------------------------------------
-- inbox_read — 알림함 읽음 표시
-- ---------------------------------------------------------------------------

-- 목록에서 빠진 항목(논지가 생겼거나 전량 매도)의 행은 지우지 않는다. 같은 item_id 가 다시 나올 일이 없어
-- 남아도 무해하고, 지우는 배치를 둘 이유가 없다. 재매수는 item_id 가 바뀌어 새 항목이 된다.
CREATE TABLE inbox_read (
    user_id BIGINT      NOT NULL,
    -- 서버가 만든 불투명 식별자 (지금은 record-{종목코드}-{tradeId}). 길이 상한은 apiSpec §6.4 의 64자다.
    item_id VARCHAR(64) NOT NULL,
    read_at TIMESTAMPTZ NOT NULL,

    -- 같은 항목을 두 번 읽어도 한 행이다. 읽음 표시가 멱등인 근거가 이 PK 다 (INSERT ... ON CONFLICT DO NOTHING).
    CONSTRAINT pk_inbox_read      PRIMARY KEY (user_id, item_id),
    CONSTRAINT fk_inbox_read_user FOREIGN KEY (user_id) REFERENCES users (id)
);
