-- 방의 사과 (Q-19, 11/9 지영): 문 장치(QR·키패드 게이트웨이) 오류로 입실을 못 해서 생긴 노쇼를 사람 없이 무른다.
-- 장치는 끊긴 동안 오류 구간과 그동안 눌린 번호를 모아 뒀다가 다시 연결되면 보낸다 (같은 eventId로 다시 보내도 한 번만).
-- 번호는 비밀이라 저장하지 않는다. 받을 때 "그 시간 그 방 예약"을 찾아 예약 id만 남긴다. 키패드 실패 횟수(5번 잠금)에도 세지 않는다.
-- V21은 core #97, V22는 core #98이 쓴다
CREATE TABLE device_fault (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    varchar(64)  NOT NULL UNIQUE,
    room_id     smallint     NOT NULL REFERENCES rooms(id),
    device_id   varchar(50)  NOT NULL,
    kind        varchar(30)  NOT NULL CHECK (kind IN ('SERVER_UNREACHABLE', 'SERVER_ERROR', 'DEVICE_RESTART',
                                                      'CAMERA_DOWN', 'KEYPAD_DOWN')),
    started_at  timestamptz  NOT NULL,
    ended_at    timestamptz  NOT NULL,
    received_at timestamptz  NOT NULL,
    CONSTRAINT fault_period CHECK (started_at <= ended_at)
);
CREATE INDEX device_fault_room_time ON device_fault (room_id, started_at);
-- 방별 하루 번호 수 상한을 셀 때
CREATE INDEX device_fault_room_received ON device_fault (room_id, received_at);

-- 오류 구간 동안 눌린 번호 한 번 = 한 줄. 맞는 예약을 못 찾으면 reservation_id는 비운다
CREATE TABLE device_fault_attempt (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fault_id       bigint       NOT NULL REFERENCES device_fault(id) ON DELETE CASCADE,
    attempted_at   timestamptz  NOT NULL,
    via            varchar(10)  NOT NULL CHECK (via IN ('KEYPAD', 'QR')),
    reservation_id bigint       REFERENCES reservation(id)
);
CREATE INDEX device_fault_attempt_reservation ON device_fault_attempt (reservation_id) WHERE reservation_id IS NOT NULL;

-- 무른 노쇼: 상태는 NO_SHOW 그대로(기록·통계), 벌칙 세기에서만 뺀다
ALTER TABLE reservation
    ADD COLUMN no_show_excused_at timestamptz,
    ADD CONSTRAINT excused_is_no_show CHECK (no_show_excused_at IS NULL OR status = 'NO_SHOW');
