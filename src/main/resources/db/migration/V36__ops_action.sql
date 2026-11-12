-- AI 자동 처리 기록 (11/12 지영 "관리자 카드 다 AI가 처리하게, 관리자는 어떻게 처리됐나만 봄")
-- 숫자 규칙(OpsRules)이 1분마다 보고 처리한 일. 학생 알림 3종은 알림도 같이 보내고, 관리자용 2종은 기록만 한다.
-- 같은 일을 두 번 남기지 않게 (kind, room_id, started_at)을 하나로 둔다:
--   VENT_SOON·ROOM_HOT·OVERTIME = 그 예약 시작 시각 (예약마다 1번)
--   SENSOR_DOWN = 마지막으로 신호를 받은 시각 (끊김마다 1번)
--   UNBOOKED    = "있음"이 시작된 시각 (이어진 한 번마다 1번)
CREATE TABLE ops_action (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind           varchar(20)  NOT NULL
        CHECK (kind IN ('VENT_SOON', 'ROOM_HOT', 'SENSOR_DOWN', 'OVERTIME', 'UNBOOKED')),
    room_id        smallint     NOT NULL REFERENCES rooms(id),
    reservation_id bigint       REFERENCES reservation(id) ON DELETE SET NULL,
    started_at     timestamptz  NOT NULL,
    detected_at    timestamptz  NOT NULL,
    detail         varchar(200) NOT NULL,   -- 무엇을 보고 정했나 (숫자만, 이름·학번 없음)
    notified       smallint     NOT NULL DEFAULT 0 CHECK (notified >= 0),   -- 알림 보낸 사람 수 (관리자용은 0)
    CONSTRAINT ops_action_once UNIQUE (kind, room_id, started_at)
);

-- 관리자 "AI가 처리한 일"·하루 운영 보고: 날짜 범위로 읽는다
CREATE INDEX ops_action_detected ON ops_action (detected_at DESC);
