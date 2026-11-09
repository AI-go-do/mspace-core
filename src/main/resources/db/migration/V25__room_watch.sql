-- 빈방 알림 (11/9 지영 "알림 만들자"): 사용 중인 방에 "비면 알려 줘요"를 켜 두면, 예약이 끝나 방이 비는 순간(1분 정리) 알림함으로 알린다.
-- "비었다" = 지금 시각을 덮는 끝나지 않은 예약(BOOKED·IN_USE)이 없음 (화면 빈방 표시 occupiedNow와 같은 기준, 11/9 지영 선택).
-- 켜 둔 날 하루만. 한 번 알리면 끝난다. V24는 core #101(빈방 릴레이)
CREATE TABLE room_watch (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES users(id),
    room_id     smallint    NOT NULL REFERENCES rooms(id),
    watch_date  date        NOT NULL,                    -- 서울 날짜. 날이 바뀌면 더 알리지 않는다
    created_at  timestamptz NOT NULL,
    notified_at timestamptz,                             -- 알린 시각
    canceled_at timestamptz,                             -- 끈 시각
    CONSTRAINT watch_done_once CHECK (notified_at IS NULL OR canceled_at IS NULL)
);
-- 같은 사람·같은 방·같은 날 켜진 것은 하나
CREATE UNIQUE INDEX one_watch_per_room ON room_watch (user_id, room_id, watch_date)
    WHERE notified_at IS NULL AND canceled_at IS NULL;
-- 1분 정리가 켜진 것만 훑는다
CREATE INDEX room_watch_active ON room_watch (watch_date, room_id) WHERE notified_at IS NULL AND canceled_at IS NULL;
