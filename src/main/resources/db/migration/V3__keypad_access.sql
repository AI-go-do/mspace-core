-- 키패드 체크인 (FR-ACC-001, #8)

-- 같은 방의 끝나지 않은 예약끼리 번호가 겹치지 않는다 (명세 FR-ACC-001).
-- 키패드는 방 번호 + 6자리 번호로 예약을 찾으므로, 겹치면 누구 예약인지 알 수 없다
CREATE UNIQUE INDEX one_active_code_per_room
    ON reservation (room_id, access_code) WHERE status IN ('BOOKED', 'IN_USE');

-- 키패드 입력 기록. 장치가 같은 이벤트를 다시 보내도(와이파이 끊김) event_id로 한 번만 처리하고 처음 결과를 돌려준다.
-- 입력한 번호는 남기지 않는다 (남의 번호가 기록에 쌓이지 않게)
CREATE TABLE access_event (
    event_id       varchar(64) PRIMARY KEY,
    room_id        smallint    NOT NULL REFERENCES rooms(id),
    device_id      varchar(50),
    result         varchar(10) NOT NULL CHECK (result IN ('OPENED', 'DENIED')),
    reason         varchar(20) CHECK (reason IN ('WRONG_CODE', 'NOT_YET', 'LOCKED')),
    reservation_id bigint      REFERENCES reservation(id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT denied_has_reason CHECK ((result = 'DENIED') = (reason IS NOT NULL))
);

-- 방마다 연속으로 틀린 횟수와 잠금 시각. 5번 연속 틀리면 그 방 키패드를 1분 잠근다.
-- 같은 방 입력은 이 줄을 FOR UPDATE로 잡고 한 줄로 처리한다 (횟수를 세다 겹치지 않게)
CREATE TABLE keypad_lock (
    room_id      smallint    PRIMARY KEY REFERENCES rooms(id),
    failed_count smallint    NOT NULL DEFAULT 0,
    locked_until timestamptz
);
INSERT INTO keypad_lock (room_id) SELECT id FROM rooms;
