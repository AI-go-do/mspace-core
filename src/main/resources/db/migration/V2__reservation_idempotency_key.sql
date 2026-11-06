-- 예약 만들기 멱등 키 (#10, 영준 비서 M2 합의 11/2)
-- 같은 대표자가 같은 Idempotency-Key로 다시 보내면 새로 만들지 않고 처음 예약을 돌려준다.
-- 키 없이 만든 예약(웹)은 NULL이고, UNIQUE는 NULL끼리 겹쳐도 막지 않는다.
ALTER TABLE reservation ADD COLUMN idempotency_key varchar(64);

ALTER TABLE reservation
    ADD CONSTRAINT one_reservation_per_key UNIQUE (owner_id, idempotency_key);
