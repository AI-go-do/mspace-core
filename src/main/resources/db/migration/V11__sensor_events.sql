-- 재실·CO2 센서 (FR-DEV-101~104). 형식은 mspace-device docs/sensor-events-api.md (수연 초안, 11/5 지영 확정)

-- 1) 자리 비움 자동 퇴실 상태 AWAY_RELEASED (FR-DEV-103). 퇴실이라 checked_out_at이 있다
ALTER TABLE reservation DROP CONSTRAINT reservation_status_check;
ALTER TABLE reservation ADD CONSTRAINT reservation_status_check CHECK (status IN
    ('BOOKED', 'IN_USE', 'CHECKED_OUT', 'AUTO_CHECKED_OUT', 'AWAY_RELEASED', 'NO_SHOW', 'CANCELED'));
ALTER TABLE reservation DROP CONSTRAINT checked_out_has_time;
ALTER TABLE reservation ADD CONSTRAINT checked_out_has_time CHECK (
    (status IN ('CHECKED_OUT', 'AUTO_CHECKED_OUT', 'AWAY_RELEASED')) = (checked_out_at IS NOT NULL));
ALTER TABLE reservation DROP CONSTRAINT in_use_has_checkin;
ALTER TABLE reservation ADD CONSTRAINT in_use_has_checkin CHECK (
    status NOT IN ('IN_USE', 'CHECKED_OUT', 'AUTO_CHECKED_OUT', 'AWAY_RELEASED') OR checked_in_at IS NOT NULL);

-- 상태 순서 트리거에 IN_USE → AWAY_RELEASED 추가 (나머지는 V1과 같음)
CREATE OR REPLACE FUNCTION reservation_status_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.status = OLD.status THEN RETURN NEW; END IF;
    IF OLD.status = 'BOOKED' AND NEW.status = 'CANCELED' AND now() >= OLD.start_at THEN
        RAISE EXCEPTION '시작한 예약은 취소할 수 없습니다' USING ERRCODE = 'check_violation';
    END IF;
    IF (OLD.status, NEW.status) IN (('BOOKED', 'IN_USE'), ('BOOKED', 'NO_SHOW'), ('BOOKED', 'CANCELED'),
                                    ('IN_USE', 'CHECKED_OUT'), ('IN_USE', 'AUTO_CHECKED_OUT'),
                                    ('IN_USE', 'AWAY_RELEASED')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '허용되지 않는 상태 변경: % → %', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

-- 2) 자리 비움 10분 알림을 예약·사람별 한 번만 (NFR-NTF-101과 같은 방식)
ALTER TABLE notification_log DROP CONSTRAINT notification_log_kind_check;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_kind_check
    CHECK (kind IN ('START_SOON', 'END_SOON', 'NO_SHOW_SOON', 'AWAY_SOON'));

-- 3) 센서 이벤트. 재실은 상태가 바뀐 신호만, CO2는 모든 값을 남긴다 (10초 살아 있음 신호는 room_presence만 고침)
--    event_id unique: 장치가 같은 신호를 다시 보내도 한 번만 (키패드 access_event와 같음)
CREATE TABLE sensor_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    varchar(64)  NOT NULL UNIQUE,
    room_id     smallint     NOT NULL REFERENCES rooms(id),
    device_id   varchar(50)  NOT NULL,
    kind        varchar(20)  NOT NULL CHECK (kind IN ('OCCUPANCY', 'CO2')),
    occupied    boolean,
    sources     jsonb,                  -- 재실: 센서별 값 (정확도 측정용, 판단에는 안 씀)
    ppm         integer CHECK (ppm BETWEEN 0 AND 10000),
    source      varchar(30),            -- CO2: 센서 이름
    observed_at timestamptz  NOT NULL,  -- 센서가 본 시각. 10·15분과 CO2 연속은 이 시각으로 잰다
    received_at timestamptz  NOT NULL,
    CONSTRAINT kind_has_value CHECK (
        (kind = 'OCCUPANCY' AND occupied IS NOT NULL AND ppm IS NULL)
     OR (kind = 'CO2' AND ppm IS NOT NULL AND occupied IS NULL))
);

-- CO2 연속 판단: 방별 최근 값
CREATE INDEX sensor_event_room_recent ON sensor_event (room_id, kind, observed_at DESC);

-- 4) 방 지금 상태 (FR-DEV-102·103). 방마다 한 줄, 센서 이벤트가 올 때 이 줄을 잠그고 고친다
CREATE TABLE room_presence (
    room_id          smallint    PRIMARY KEY REFERENCES rooms(id),
    occupied         boolean     NOT NULL,
    changed_at       timestamptz NOT NULL,  -- 지금 상태가 된 observedAt (비어 있음 10·15분은 여기부터)
    trusted_from     timestamptz NOT NULL,  -- 끊겼다 돌아온 시각(받은 시각). 이보다 오래된 관측으로는 타이머를 당기지 않는다
    last_observed_at timestamptz NOT NULL,  -- 가장 최근 재실 관측 시각. 이보다 오래된(늦게 온) 신호는 상태를 덮지 않는다
    last_received_at timestamptz NOT NULL,  -- 마지막으로 받은 시각(서버 시계). 1분 넘게 없으면 끊김 → "있음"으로 본다
    ventilation_alert_at timestamptz         -- 마지막 환기 알림의 observedAt (같은 방 30분에 한 번)
);
