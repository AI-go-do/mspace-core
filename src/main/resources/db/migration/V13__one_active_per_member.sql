-- FR-RSV-003 (11/5 지영 결정, docs v0.4.9): 끝나지 않은 예약 1건을 대표자뿐 아니라 참여자에게도 적용한다.
-- 참여자로 15–17시 예약이 있는 사람이 대표자로 19–21시 예약을 또 잡거나, 다른 예약의 참여자로 또 들어가는 것을 막는다.
--
-- reservation_participant에는 대표자도 들어 있고(is_owner), active는 예약 상태가 BOOKED·IN_USE일 때 true로
-- 트리거가 맞춰 준다. 그래서 "active인 줄은 회원당 1개"만 걸면 대표자·참여자 둘 다 막힌다.
-- 퇴실·취소·노쇼가 되면 active가 false가 되어 바로 풀린다.
--
-- 배포 전 확인: 이 규칙 전에 만든 예약 중 한 회원이 끝나지 않은 예약 2건 이상에 들어가 있으면 인덱스를 만들 수 없다.
-- 마이그레이션이 데이터를 마음대로 취소하지 않고, 걸리는 회원·예약 id를 오류 메시지로 알려 주고 멈춘다(앱 시작 실패).
-- 정리 방법(docs/결정.md 13번): 시작 전 예약은 대표자가 취소, 사용 중이면 퇴실, 아니면 예약이 끝나 자동 정리된 뒤 다시 배포.
-- 미리 보는 쿼리:
--   SELECT user_id, array_agg(reservation_id ORDER BY start_at) FROM reservation_participant
--    WHERE active GROUP BY user_id HAVING count(*) > 1;
DO $$
DECLARE conflicts text;
BEGIN
    SELECT string_agg(format('회원 %s → 예약 %s', user_id, ids), '; ')
      INTO conflicts
      FROM (SELECT user_id, array_agg(reservation_id ORDER BY start_at)::text AS ids
              FROM reservation_participant WHERE active
             GROUP BY user_id HAVING count(*) > 1) d;
    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION 'V13: 끝나지 않은 예약 2건 이상에 들어간 회원이 있어 1인 1건 인덱스를 만들 수 없습니다. 먼저 정리하세요(docs/결정.md 13번): %', conflicts;
    END IF;
END $$;

CREATE UNIQUE INDEX one_active_per_member
    ON reservation_participant (user_id) WHERE active;
