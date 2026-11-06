-- 관리자 강제 퇴실 (FR-RPT-104 퇴출)
-- 관리자가 사용 중(IN_USE)인 예약을 끝낸다. 상태는 보통 퇴실과 같은 CHECKED_OUT이라
-- 상태 순서 트리거·방 겹침·참여자 시간 풀기가 그대로 돈다. 누가 왜 끝냈는지만 따로 남긴다
ALTER TABLE reservation
    ADD COLUMN forced_by     bigint       REFERENCES users(id),
    ADD COLUMN forced_reason varchar(200),
    ADD CONSTRAINT forced_is_checkout CHECK (forced_by IS NULL OR status = 'CHECKED_OUT'),
    ADD CONSTRAINT forced_reason_has_admin CHECK (forced_reason IS NULL OR forced_by IS NOT NULL);

