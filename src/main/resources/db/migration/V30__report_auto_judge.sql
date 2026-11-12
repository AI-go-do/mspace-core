-- 신고 자동 판정 (FR-RPT-103 10차: 관리자는 판정하지 않고 판정기가 정함, Q-28)
-- 판정기(ReportJudge 포트 뒤 규칙·AI 어댑터)가 낸 판정과 확신도·이유를 남긴다. 정답 세트로 판정기별 정확도를 재려고
-- (NFR-AGT-101). 판정 자체는 report.verdict에, 판정한 관리자가 없으므로 judged_by는 NULL
CREATE TABLE report_judgement (
    report_id       bigint        PRIMARY KEY REFERENCES report(id),
    judge           varchar(60)   NOT NULL,          -- 판정기 이름(모델). 규칙 판정기는 rules
    prompt_version  varchar(40)   NOT NULL,
    verdict         varchar(12)   NOT NULL CHECK (verdict IN ('CONFIRMED', 'UNVERIFIED', 'FALSE')),  -- 판정기가 낸 판정
    confidence      real          NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    applied         varchar(12)   NOT NULL CHECK (applied IN ('CONFIRMED', 'UNVERIFIED', 'FALSE')),   -- 확신도 기준 적용 뒤
    reasons         varchar(1000) NOT NULL,
    evidence_used   text[]        NOT NULL,
    created_at      timestamptz   NOT NULL
);

-- 1분마다 판정 대기 학생 신고 고르기: 대기 중인 것만, 오래된 것부터
CREATE INDEX report_open_recent ON report (created_at) WHERE verdict = 'OPEN';
