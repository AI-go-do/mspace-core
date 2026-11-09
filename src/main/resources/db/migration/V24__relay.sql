-- 빈방 릴레이 (Q-18, 11/9 지영): 차 있는 30분 칸에 "기다리기"를 걸어 두면, 그 예약이 취소될 때 기다린 순서대로 한 사람씩 넘긴다.
-- 넘겨받은 사람만 정해진 시간(5분) 동안 그 구간을 예약할 수 있고, "넘길게요"·시간 지남이면 다음 사람에게 간다.
-- 시작 30분 전부터는 넘기지 않고 그냥 빈 칸으로 푼다. V21–V23은 core #97·#98·#99가 쓴다
CREATE TABLE relay_wait (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint       NOT NULL REFERENCES users(id),
    room_id    smallint     NOT NULL REFERENCES rooms(id),
    start_at   timestamptz  NOT NULL,                      -- 기다리는 30분 칸의 시작
    status     varchar(10)  NOT NULL DEFAULT 'WAITING'
               CHECK (status IN ('WAITING', 'OFFERED', 'TAKEN', 'DECLINED', 'CANCELED')),
    created_at timestamptz  NOT NULL,                      -- 줄 선 순서
    ended_at   timestamptz,
    CONSTRAINT wait_ended CHECK ((status IN ('WAITING', 'OFFERED')) = (ended_at IS NULL))
);
-- 같은 사람이 같은 칸에 두 번 줄 서지 않게. 줄 순서도 이 인덱스로 찾는다
CREATE UNIQUE INDEX one_wait_per_slot ON relay_wait (user_id, room_id, start_at) WHERE status IN ('WAITING', 'OFFERED');
CREATE INDEX relay_wait_queue ON relay_wait (room_id, start_at, created_at, id) WHERE status = 'WAITING';

CREATE TABLE relay_offer (
    id                    bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wait_id               bigint       NOT NULL REFERENCES relay_wait(id),
    user_id               bigint       NOT NULL REFERENCES users(id),
    room_id               smallint     NOT NULL REFERENCES rooms(id),
    start_at              timestamptz  NOT NULL,             -- 놓인(취소된) 예약 구간
    end_at                timestamptz  NOT NULL,
    source_reservation_id bigint       NOT NULL REFERENCES reservation(id),
    created_at            timestamptz  NOT NULL,
    expires_at            timestamptz  NOT NULL,             -- 이때까지 안 받으면 다음 사람
    status                varchar(10)  NOT NULL DEFAULT 'OPEN'
                          CHECK (status IN ('OPEN', 'TAKEN', 'DECLINED', 'EXPIRED')),
    passed_on             boolean      NOT NULL DEFAULT false, -- 넘김·시간 지남 뒤 다음 사람 찾기를 마쳤는지
    reservation_id        bigint       REFERENCES reservation(id), -- 받아서 만든 예약
    CONSTRAINT offer_period CHECK (start_at < end_at),
    CONSTRAINT offer_taken_has_reservation CHECK ((status = 'TAKEN') = (reservation_id IS NOT NULL)),
    -- 같은 방·같은 시간에 열린 넘김은 하나 (넘겨받은 한 사람만 잡을 수 있게)
    CONSTRAINT one_open_offer EXCLUDE USING gist (room_id WITH =, tstzrange(start_at, end_at) WITH &&)
        WHERE (status = 'OPEN')
);
CREATE INDEX relay_offer_user ON relay_offer (user_id) WHERE status = 'OPEN';
CREATE INDEX relay_offer_to_pass ON relay_offer (id) WHERE status IN ('DECLINED', 'EXPIRED') AND NOT passed_on;
