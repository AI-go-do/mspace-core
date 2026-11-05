-- FR-RSV-003 (11/5 지영 결정, docs v0.4.9): 끝나지 않은 예약 1건을 대표자뿐 아니라 참여자에게도 적용한다.
-- 참여자로 15–17시 예약이 있는 사람이 대표자로 19–21시 예약을 또 잡거나, 다른 예약의 참여자로 또 들어가는 것을 막는다.
--
-- reservation_participant에는 대표자도 들어 있고(is_owner), active는 예약 상태가 BOOKED·IN_USE일 때 true로
-- 트리거가 맞춰 준다. 그래서 "active인 줄은 회원당 1개"만 걸면 대표자·참여자 둘 다 막힌다.
-- 퇴실·취소·노쇼가 되면 active가 false가 되어 바로 풀린다.
--
-- 주의: 이미 한 회원이 끝나지 않은 예약 2건에 들어가 있으면 이 인덱스는 만들어지지 않는다(마이그레이션 실패).
-- 개발 서버에 그런 테스트 예약이 있으면 배포 전에 취소하거나 끝난 뒤에 배포한다.
CREATE UNIQUE INDEX one_active_per_member
    ON reservation_participant (user_id) WHERE active;
