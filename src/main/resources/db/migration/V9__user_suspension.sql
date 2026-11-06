-- 노쇼 벌칙 (FR-RSV-106, 11/4 지영 결정): 30일 안에 노쇼 2번이면 14일 동안 예약·참여 못 함
-- 정지 한 건 = 한 줄. 이유는 지금 노쇼뿐이고, 허위 신고 정지(FR-RPT-103 벌칙)가 정해지면 CHECK에 값을 더한다(새 번호 파일로)
CREATE TABLE user_suspension (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint       NOT NULL REFERENCES users(id),
    reason      varchar(20)  NOT NULL CHECK (reason IN ('NO_SHOW')),
    starts_at   timestamptz  NOT NULL,
    ends_at     timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT suspension_period CHECK (starts_at < ends_at),
    -- 같은 사람 정지 기간이 겹치면 DB가 거절한다. 정리(1분 주기)와 예약 만들기 전 정리가 동시에 돌아
    -- 둘 다 "2번째 노쇼"를 봐도 INSERT ... ON CONFLICT DO NOTHING으로 정지는 한 줄만 생긴다
    CONSTRAINT one_suspension_at_a_time EXCLUDE USING gist (user_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
);
