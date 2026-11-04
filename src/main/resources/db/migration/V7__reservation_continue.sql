-- 연장하기 (FR-RSV-105, 요구사항 v0.4.3 Q-12 C안)
-- 연장은 "지금 예약 퇴실 + 새 예약을 바로 IN_USE로" 한 트랜잭션. 새 예약이 어느 예약에서 이어졌는지 남긴다
ALTER TABLE reservation
    ADD COLUMN continued_from_id bigint REFERENCES reservation(id);

-- 한 예약에서 연장은 한 번만. 서버 검사가 먼저 막고, 동시에 두 번 눌러도 DB가 마지막에 막는다
CREATE UNIQUE INDEX one_continuation_per_reservation
    ON reservation (continued_from_id) WHERE continued_from_id IS NOT NULL;

-- "같은 대표자의 직전 예약이 30분 안에 끝났나"(이어진 사용) 조회용. 연장·예약 만들기 때마다 돈다
CREATE INDEX reservation_owner_end ON reservation (owner_id, end_at);
