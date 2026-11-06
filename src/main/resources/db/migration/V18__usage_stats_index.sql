-- 관리자 이용 통계 (FR-WEB-101): 기간 안에 시작한 예약을 방별로 고른다 (통계 조회 때만 쓰임)
CREATE INDEX reservation_room_start ON reservation (room_id, start_at);
