-- 퇴실 뒤 정리 확인 (FR-DEV-105, 11/6 지영·수연). 방 카메라(젯슨)는 사진을 남기지 않고 결과·물건 이름만 보낸다.
-- 치울 것이 있으면 core가 어질러짐 신고를 자동으로 만들고(신고자 없음) 관리자가 판정한다 (11/6 지영 "자동신고+관리자 판정")

-- 1) 신고에 "누가 냈나"를 더한다. 카메라 신고는 신고자·근거 예약이 없고, 경고 대상 예약(target)을 바로 안다
ALTER TABLE report ALTER COLUMN reporter_id DROP NOT NULL;
ALTER TABLE report ALTER COLUMN reporter_reservation_id DROP NOT NULL;
ALTER TABLE report ADD COLUMN source varchar(10) NOT NULL DEFAULT 'USER' CHECK (source IN ('USER', 'CAMERA'));
ALTER TABLE report ADD COLUMN target_reservation_id bigint REFERENCES reservation(id);
ALTER TABLE report ADD CONSTRAINT report_source_shape CHECK (
    (source = 'USER' AND reporter_id IS NOT NULL AND reporter_reservation_id IS NOT NULL AND target_reservation_id IS NULL)
    OR (source = 'CAMERA' AND reporter_id IS NULL AND reporter_reservation_id IS NULL
        AND target_reservation_id IS NOT NULL AND category = 'MESSY'));

-- 2) 정리 확인 결과: 예약 하나에 하나, 같은 eventId는 한 번만
CREATE TABLE tidy_check (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       varchar(64)  NOT NULL UNIQUE,
    reservation_id bigint       NOT NULL UNIQUE REFERENCES reservation(id),
    room_id        smallint     NOT NULL REFERENCES rooms(id),
    device_id      varchar(50)  NOT NULL,
    result         varchar(16)  NOT NULL CHECK (result IN ('CLEAN', 'NEEDS_CLEANING')),
    items          text[]       NOT NULL,   -- 본 물건 이름 전부 (모델이 내는 이름 그대로)
    left_items     text[]       NOT NULL CHECK (left_items <@ ARRAY['BAG', 'PHONE', 'EARBUDS']),  -- 두고 간 귀중품
    report_id      bigint       UNIQUE REFERENCES report(id),
    observed_at    timestamptz  NOT NULL,
    received_at    timestamptz  NOT NULL,
    CONSTRAINT cleaning_has_report CHECK ((result = 'NEEDS_CLEANING') = (report_id IS NOT NULL))
);

-- 관리자 목록: 최근 것부터
CREATE INDEX tidy_check_recent ON tidy_check (received_at DESC);
