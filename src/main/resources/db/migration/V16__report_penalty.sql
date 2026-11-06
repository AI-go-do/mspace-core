-- 신고 판정 뒤 벌칙 (FR-RPT-103·104, 11/6 지영 "docs 제안대로")
-- 사실(CONFIRMED) → 신고 직전에 그 방을 쓴 예약의 대표자에게 경고(VIOLATION). 30일 안 3번이면 14일 이용 정지
-- 허위(FALSE)     → 신고자에게 경고(FALSE_REPORT). 30일 안 3번이면 14일 신고 정지
-- 확인 불가(UNVERIFIED) → 벌칙 없음

-- 1) 경고 기록: 판정 하나에 경고 하나 (판정은 조건부 UPDATE로 한 번뿐이지만 DB에서도 막는다)
CREATE TABLE user_warning (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        bigint       NOT NULL REFERENCES users(id),
    reason         varchar(20)  NOT NULL CHECK (reason IN ('VIOLATION', 'FALSE_REPORT')),
    report_id      bigint       NOT NULL REFERENCES report(id),
    reservation_id bigint       REFERENCES reservation(id),   -- 사실 판정일 때 경고 받은 예약. 허위는 비움
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT one_warning_per_report UNIQUE (report_id)
);

-- 30일 안 경고 세기: 한 사람·한 종류의 최근 줄만 훑는다
CREATE INDEX user_warning_recent ON user_warning (user_id, reason, created_at DESC);

-- 2) 이용 정지 사유에 "경고 누적"을 더한다. 기간 겹침 금지(one_suspension_at_a_time)는 그대로라
--    노쇼 정지와 경고 정지가 동시에 생기려 하면 한 줄만 남는다
ALTER TABLE user_suspension DROP CONSTRAINT user_suspension_reason_check;
ALTER TABLE user_suspension ADD CONSTRAINT user_suspension_reason_check CHECK (reason IN ('NO_SHOW', 'WARNINGS'));

-- 3) 신고 정지: 예약은 되고 신고만 못 한다. 이용 정지와 따로 두어 두 정지가 서로 막지 않게
CREATE TABLE report_suspension (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint       NOT NULL REFERENCES users(id),
    starts_at   timestamptz  NOT NULL,
    ends_at     timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT report_suspension_period CHECK (starts_at < ends_at),
    CONSTRAINT one_report_suspension_at_a_time EXCLUDE USING gist (user_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
);
