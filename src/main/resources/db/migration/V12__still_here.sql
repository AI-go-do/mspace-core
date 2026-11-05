-- "아직 있어요" 버튼 (FR-DEV-103, FR-NTF-103). 11/5 지영: 예약당 1번(docs대로), 실제 서비스에도 넣음
-- 누르면 그 예약의 자리 비움 타이머가 누른 시각부터 다시 돈다. 누른 뒤에는 10분 비면 알림 없이 바로 퇴실 (11/5 지영)
ALTER TABLE reservation ADD COLUMN still_here_at timestamptz;
