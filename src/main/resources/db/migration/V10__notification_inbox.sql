-- 예약 알림 3종 (FR-NTF-101 입실 10분 전, FR-NTF-102 종료 10분 전, FR-NTF-104 노쇼 5분 전, 11/5 지영 담당)
-- 1) notification_log: 노쇼 직전 안내(NO_SHOW_SOON)를 받을 수 있게 종류를 늘린다. PK(예약, 사람, 종류)가 "한 번만"을 지킨다 (NFR-NTF-101)
ALTER TABLE notification_log DROP CONSTRAINT notification_log_kind_check;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_kind_check
    CHECK (kind IN ('START_SOON', 'END_SOON', 'NO_SHOW_SOON'));

-- 2) 앱 알림함 (FR-NTF-103). 메일(재웅 담당)이 늦거나 실패해도 알림 내용은 여기 남는다.
--    문구에는 이름·학번을 넣지 않는다 (방 이름·시각만)
CREATE TABLE notification (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        bigint       NOT NULL REFERENCES users(id),
    reservation_id bigint       REFERENCES reservation(id) ON DELETE CASCADE,   -- 예약과 상관없는 알림(신고 결과 등)은 비움
    kind           varchar(20)  NOT NULL,
    title          varchar(100) NOT NULL,
    body           varchar(300) NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    read_at        timestamptz
);

-- 내 알림함: 최근 것부터
CREATE INDEX notification_user_recent ON notification (user_id, created_at DESC);
