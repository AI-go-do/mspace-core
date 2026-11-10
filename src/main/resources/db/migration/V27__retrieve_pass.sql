-- 두고 간 물건 찾으러 들어가는 10분 QR (FR-DEV-105, 11/10 지영 "찾으러 갈게요 누르면 10분 QR",
-- "다음 팀이 쓰고 있어도 퇴실 뒤 3시간")
-- 퇴실 직후 확인(FIRST)이 LEFT_ITEMS인 예약의 대표자·참여자가 퇴실 뒤 3시간 안에 받는다.
-- 예약마다 한 번에 하나만 살아 있고(서비스가 예약 줄을 잠그고 확인), 10분 안에는 문을 여러 번 열 수 있다.
-- first_used_at: 처음 문을 연 시각. "3시간 안에 찾으러 왔나"를 나중에 경고 판단에 쓸 수 있게 남긴다
CREATE TABLE retrieve_pass (
    id             bigserial   PRIMARY KEY,
    reservation_id bigint      NOT NULL REFERENCES reservation(id),
    room_id        smallint    NOT NULL REFERENCES rooms(id),
    issued_by      bigint      NOT NULL REFERENCES users(id),
    code           char(6)     NOT NULL CHECK (code ~ '^[0-9]{6}$'),
    issued_at      timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    first_used_at  timestamptz,
    CONSTRAINT retrieve_pass_expires_after_issue CHECK (expires_at > issued_at)
);

-- 문 앞에서 방 번호 + 6자리로 찾는다
CREATE INDEX retrieve_pass_room_code ON retrieve_pass (room_id, code, expires_at);
-- 그 예약에 살아 있는 QR이 있는지
CREATE INDEX retrieve_pass_reservation ON retrieve_pass (reservation_id, expires_at DESC);

-- 문 열림 기록에 "무엇으로 열었나". 찾으러 온 QR이면 RETRIEVE, 보통 입실은 NULL
ALTER TABLE access_event
    ADD COLUMN purpose varchar(10) CHECK (purpose IN ('RETRIEVE')),
    ADD CONSTRAINT access_event_purpose_opened CHECK (purpose IS NULL OR result = 'OPENED');
