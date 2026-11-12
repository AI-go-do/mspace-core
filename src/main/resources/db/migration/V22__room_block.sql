-- 관리자 기간 막기 (Q-23, core #95): 공사·점검·대관 같은 이유로 방을 일정 기간 예약 못 하게 막는다.
-- 막은 칸은 예약·즉시 시작·연장이 안 된다(예약 저장 때 같은 방 잠금 안에서 확인 → 409 ROOM_BLOCKED).
-- 풀어도 줄은 지우지 않고 released_at을 남긴다. 같은 방에서 풀리지 않은 막기끼리는 겹칠 수 없다.
-- V21은 core #97(센서 온도·습도)이 쓴다
CREATE TABLE room_block (
    id          bigserial    PRIMARY KEY,
    room_id     smallint     NOT NULL REFERENCES rooms(id),
    start_at    timestamptz  NOT NULL,
    end_at      timestamptz  NOT NULL,
    reason      varchar(20)  NOT NULL CHECK (reason IN ('CONSTRUCTION', 'INSPECTION', 'RENTAL', 'OTHER')),
    memo        varchar(100),
    created_by  bigint       NOT NULL REFERENCES users(id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    released_at timestamptz,
    released_by bigint       REFERENCES users(id),
    CONSTRAINT block_valid_range CHECK (start_at < end_at),
    CONSTRAINT block_released_has_admin CHECK ((released_at IS NULL) = (released_by IS NULL)),
    -- 이 인덱스를 예약 저장 때 "막힌 칸인가" 확인도 같이 쓴다
    CONSTRAINT no_block_overlap EXCLUDE USING gist (
        room_id WITH =, tstzrange(start_at, end_at) WITH &&
    ) WHERE (released_at IS NULL)
);
