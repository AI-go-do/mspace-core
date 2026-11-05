-- "아직 있어요" 버튼 (FR-DEV-103, FR-NTF-103). 11/5 지영: 예약당 1번(docs대로), 실제 서비스에도 넣음
-- 누르면 그 예약의 자리 비움 타이머가 누른 시각부터 다시 돈다
ALTER TABLE reservation ADD COLUMN still_here_at timestamptz;

-- 버튼을 쓴 뒤 다시 10분 비면 한 번 더 알린다(버튼 없음). 예약·사람별 한 번만 (PK)
ALTER TABLE notification_log DROP CONSTRAINT notification_log_kind_check;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_kind_check
    CHECK (kind IN ('START_SOON', 'END_SOON', 'NO_SHOW_SOON', 'AWAY_SOON', 'AWAY_SOON_AGAIN'));
