-- 경고 철회 요청 (FR-RPT-105, 11/10 지영 제안·재웅 확정, Q-24 ①–③ 11/10 지영 "추천대로")
-- 학생이 받은 경고 한 건에 7일 안 1번 철회를 요청하면, 판단기(WarningReviewer 포트 뒤 AI 어댑터)가 다시 보고
-- 철회(WITHDRAWN) 또는 유지(KEPT)를 정한다. 관리자는 정하지 않는다(무인 운영)
-- ① 요청 중에도 경고는 센다 (요청으로 정지를 미루지 못하게) → 철회될 때만 빠진다
-- ② 철회되면 그 경고로 걸린 정지를 지금 끝낸다
-- ③ 확신도가 기준 아래면 "확인 불가 = 철회" (FR-RPT-103 확인 불가 = 벌칙 없음과 같은 셈)

-- 1) 철회된 경고는 세지 않는다. 줄은 지우지 않고 시각만 남긴다 (관리자 목록·기록용)
ALTER TABLE user_warning ADD COLUMN withdrawn_at timestamptz;

-- 2) 철회로 푼 정지. 끝 시각을 당기고 푼 시각을 남긴다. 푼 정지는 다음 집계 경계로 쓰지 않는다
ALTER TABLE user_suspension ADD COLUMN lifted_at timestamptz;
ALTER TABLE report_suspension ADD COLUMN lifted_at timestamptz;

-- 3) 철회 요청과 판단 기록: 경고 하나에 요청 하나
CREATE TABLE warning_appeal (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warning_id      bigint        NOT NULL UNIQUE REFERENCES user_warning(id),
    user_id         bigint        NOT NULL REFERENCES users(id),
    text            varchar(500)  NOT NULL CHECK (char_length(text) BETWEEN 10 AND 500),
    evidence        text[]        NOT NULL
                    CHECK (evidence <@ ARRAY['CHECKIN_PHOTO', 'CHECKOUT_PHOTO', 'TIDY_CHECK']),
    status          varchar(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'WITHDRAWN', 'KEPT')),
    created_at      timestamptz   NOT NULL,
    -- 판단 기록 (신뢰도 표용): 누가(모델)·어떤 지시문으로·확신도 얼마로·무엇을 보고 정했나
    decided_at      timestamptz,
    answer          varchar(1000),             -- 학생에게 보여 줄 이유 한 문단
    reviewer        varchar(60),               -- 판단기 이름(모델). 규칙 판단기는 rules
    prompt_version  varchar(40),
    confidence      real CHECK (confidence BETWEEN 0 AND 1),   -- 판단기가 낸 확신도 (③ 적용 전 원래 값)
    evidence_used   text[],
    CONSTRAINT appeal_decided_shape CHECK (
        (status = 'PENDING') = (decided_at IS NULL)
        AND (status = 'PENDING' OR (answer IS NOT NULL AND reviewer IS NOT NULL AND prompt_version IS NOT NULL
                                    AND confidence IS NOT NULL AND evidence_used IS NOT NULL)))
);

-- 판단할 요청 고르기: 대기 중인 것만, 오래된 것부터
CREATE INDEX warning_appeal_pending ON warning_appeal (created_at) WHERE status = 'PENDING';

-- 4) 결과 알림 종류 WARNING_APPEAL_RESULT(21자)가 들어가게 알림 종류 칸을 넓힌다 (V10은 20자)
ALTER TABLE notification ALTER COLUMN kind TYPE varchar(30);
