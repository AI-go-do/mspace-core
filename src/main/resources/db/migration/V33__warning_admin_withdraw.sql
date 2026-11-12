-- 관리자 경고 철회 (11/12 지영 "관리자쪽에서 경고 철회할 수 있게"). 무인 운영이 원칙이라 사람이 푼 경고는 따로 남긴다:
-- 누가(관리자 id)·왜(이유)를 경고 줄에 적는다. AI(철회 요청 판단기)가 푼 경고는 둘 다 비어 있다.
-- V32(하루 운영 보고, #122) 다음 번호라 #122가 먼저 합쳐져야 한다
ALTER TABLE user_warning ADD COLUMN withdrawn_by bigint REFERENCES users(id);
ALTER TABLE user_warning ADD COLUMN withdraw_reason varchar(500);
ALTER TABLE user_warning ADD CONSTRAINT warning_admin_withdraw_shape CHECK (
    (withdrawn_by IS NULL) = (withdraw_reason IS NULL)
    AND (withdrawn_by IS NULL OR withdrawn_at IS NOT NULL));
