-- 불 켜짐도 "AI가 처리한 일"에 남긴다 (11/13 지영: AI 칸에 불 켜짐·시간 넘김·예약 없는 사용 다 보이게)
-- LIGHT_LEFT_ON: 빈 방 조도가 기준 이상으로 10분 이어짐. started_at = 처음 켜짐을 본 시각 (켜짐마다 1번)
ALTER TABLE ops_action DROP CONSTRAINT ops_action_kind_check;
ALTER TABLE ops_action ADD CONSTRAINT ops_action_kind_check
    CHECK (kind IN ('VENT_SOON', 'ROOM_HOT', 'SENSOR_DOWN', 'OVERTIME', 'UNBOOKED', 'LIGHT_LEFT_ON'));
