-- 참여자 친구 (FR-RSV-004 변경, 11/6 지영 결정): 모르는 학번을 참여자로 넣지 못하게, 서로 수락한 친구만 참여자로 넣는다.
-- 한 쌍(두 회원)에는 줄이 하나만 있다. 요청하면 PENDING, 받은 사람이 수락하면 ACCEPTED.
-- 거절·요청 취소·친구 삭제는 줄을 지운다(다시 요청할 수 있게). 친구는 한 번 수락하면 삭제할 때까지 계속 남는다.
CREATE TABLE friendship (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requester_id  bigint      NOT NULL REFERENCES users(id),
    addressee_id  bigint      NOT NULL REFERENCES users(id),
    status        varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED')),
    requested_at  timestamptz NOT NULL,
    accepted_at   timestamptz,
    CONSTRAINT friendship_not_self CHECK (requester_id <> addressee_id),
    CONSTRAINT friendship_accepted_at CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL))
);

-- 누가 먼저 보냈든 같은 쌍은 한 줄. 동시에 서로 요청해도 하나만 들어간다
CREATE UNIQUE INDEX one_friendship_per_pair
    ON friendship (least(requester_id, addressee_id), greatest(requester_id, addressee_id));
-- 내 친구·받은 요청·보낸 요청 조회와 예약 때 친구 확인용 (양쪽 어느 칸에 내가 있든)
CREATE INDEX friendship_requester ON friendship (requester_id);
CREATE INDEX friendship_addressee ON friendship (addressee_id);
