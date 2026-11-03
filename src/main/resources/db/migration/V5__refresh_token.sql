-- 자동 로그인용 refresh 토큰 (FR-AUTH-001)
-- 액세스 토큰(JWT, 1시간)은 DB에 두지 않는다. 오래 가는 refresh 토큰(14일)만 여기 둬서, 로그아웃하면 바로 끊을 수 있게 한다.
-- 토큰 원문은 학생 폰에만 있고 DB에는 SHA-256 해시만 남긴다 (DB가 털려도 토큰으로 못 씀).
-- 한 번 쓴 토큰은 revoked_at을 채우고 새 토큰을 준다(회전). 이미 쓴 토큰이 다시 오면 도난으로 보고 그 회원 토큰을 모두 끊는다.

CREATE TABLE refresh_token (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id   bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  char(64)    NOT NULL UNIQUE,   -- SHA-256 16진수
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,                   -- 새 토큰으로 바꿨거나 로그아웃한 시각
    CHECK (expires_at > created_at)
);

-- "이 회원의 살아 있는 토큰 모두 끊기"
CREATE INDEX ix_refresh_token_member_active ON refresh_token (member_id) WHERE revoked_at IS NULL;
