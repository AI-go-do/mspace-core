-- 즉시 시작 예약은 입실 전(BOOKED)이면 누른 뒤 몇 분 안(설정값 mspace.reservation.instant-cancel.minutes, 기본 5)에 대표자가 취소할 수 있다.
-- 즉시 시작은 누른 시각이 시작이라 "시작 전 취소"(FR-RSV-007)가 한 번도 안 돼서, 잘못 누르면 노쇼(FR-RSV-106 벌칙)밖에 없었다.
-- (11/6 팀 결정 3–5분, docs #57). 미리 예약은 그대로 시작 전에만 취소된다.
-- 분 값은 앱이 조건부 UPDATE로 지키고, 트리거는 상한(입실 마감)만 지킨다
-- 상태 순서 트리거만 바꾼다. 나머지는 V11과 같다
CREATE OR REPLACE FUNCTION reservation_status_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.status = OLD.status THEN RETURN NEW; END IF;
    IF OLD.status = 'BOOKED' AND NEW.status = 'CANCELED' AND now() >= OLD.start_at
       AND NOT (OLD.is_instant AND now() <= OLD.checkin_deadline) THEN
        RAISE EXCEPTION '시작한 예약은 취소할 수 없습니다' USING ERRCODE = 'check_violation';
    END IF;
    IF (OLD.status, NEW.status) IN (('BOOKED', 'IN_USE'), ('BOOKED', 'NO_SHOW'), ('BOOKED', 'CANCELED'),
                                    ('IN_USE', 'CHECKED_OUT'), ('IN_USE', 'AUTO_CHECKED_OUT'),
                                    ('IN_USE', 'AWAY_RELEASED')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '허용되지 않는 상태 변경: % → %', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;
