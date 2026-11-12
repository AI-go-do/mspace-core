-- 관리자 하루 운영 보고 (11/12 지영 "core 다 AI화"): 하루 숫자를 AI가 3–5줄로 요약한 글을 날짜마다 한 줄 보관한다.
-- 관제 화면이 열릴 때마다 외부 AI를 부르지 않게(무료 한도) 캐시로 쓴다. 숫자는 매번 새로 세고, 글만 보관한다.
-- 이름·학번은 들어가지 않는다 (숫자와 방 이름만 보내고 받음)
CREATE TABLE daily_report (
    report_date     date          PRIMARY KEY,                       -- 서울 날짜
    lines           text[]        NOT NULL CHECK (cardinality(lines) BETWEEN 1 AND 5),
    writer          varchar(60)   NOT NULL,                          -- gemini:모델 또는 template
    prompt_version  varchar(40)   NOT NULL,
    generated_at    timestamptz   NOT NULL
);
