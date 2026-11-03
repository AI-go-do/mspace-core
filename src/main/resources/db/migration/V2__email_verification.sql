-- 학교 메일 인증번호 (FR-AUTH-001)
-- 번호는 원문 대신 해시로 둔다. 같은 주소에 여러 번 보낼 수 있고, 가장 최근 것만 쓴다.
-- 규칙(10분 유효, 5번 틀리면 끝, 1분에 1번)은 서버가 본다. 시각은 서버 Clock 기준으로 넣는다.

CREATE TABLE email_verification (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email           varchar(100) NOT NULL
                    CHECK (email ~* '^[^@\s]+@chosun\.ac\.kr$'),
    code_hash       char(64)     NOT NULL,   -- SHA-256 16진수
    created_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    failed_attempts integer      NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    used_at         timestamptz,             -- 로그인에 쓴 시각. 쓴 번호는 다시 못 쓴다
    CHECK (expires_at > created_at)
);

-- "이 주소로 가장 최근에 보낸 번호" 찾기
CREATE INDEX ix_email_verification_email_created ON email_verification (email, created_at DESC);
