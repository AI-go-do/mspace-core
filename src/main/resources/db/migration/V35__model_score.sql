-- 방 카메라 모델 시험 점수 (11/12 재웅·지영 "관리자 오디 AI 상자의 모델 시험 정확도를 젯슨이 채점한 진짜 값으로").
-- 젯슨이 tidy_eval.py score를 끝내면 숫자 한 줄을 보낸다. 사진·문제집은 보내지 않는다
CREATE TABLE model_score (
    id              bigserial     PRIMARY KEY,
    event_id        varchar(64)   NOT NULL UNIQUE,                   -- 같은 결과를 다시 보내도 한 줄
    kind            varchar(20)   NOT NULL CHECK (kind IN ('TIDY')),
    model           varchar(60)   NOT NULL,
    accuracy        real          NOT NULL CHECK (accuracy BETWEEN 0 AND 1),    -- 깨끗함/남음 정확도(화면 값)
    accuracy3       real          CHECK (accuracy3 BETWEEN 0 AND 1),            -- 세 가지 정확도(참고)
    cases           integer       NOT NULL CHECK (cases >= 1),
    serious         integer       CHECK (serious >= 0),
    median_seconds  real          NOT NULL CHECK (median_seconds >= 0),
    measured_at     timestamptz   NOT NULL,
    room_id         integer       NOT NULL REFERENCES rooms (id),
    device_id       varchar(50),
    received_at     timestamptz   NOT NULL
);

CREATE INDEX model_score_recent ON model_score (kind, measured_at DESC);
