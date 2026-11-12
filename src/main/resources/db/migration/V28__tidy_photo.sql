-- 방 카메라 사진 (FR-DEV-105, docs Q-27. 11/10 10차 회의 "사람이 0명인 빈 방·물건 사진은 저장 가능",
-- 같은 날 지영 "퇴실 사진은 core에 올려 30일 보관")
-- 젯슨이 정리 확인(FIRST·RECHECK) 때 본 빈 방 화면 1장씩. 학생 사진(reservation_photo)과 같은 방식으로 DB에 두고
-- 같은 1시간 정리가 올린 지 30일 지나면 지운다. 올린 사람이 학생이 아니라 장치라 표를 나눈다
-- (reservation_photo.uploaded_by는 회원 NOT NULL)
CREATE TABLE tidy_photo (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id bigint      NOT NULL REFERENCES reservation(id),
    phase          varchar(8)  NOT NULL CHECK (phase IN ('FIRST', 'RECHECK')),
    device_id      varchar(50) NOT NULL,
    content_type   varchar(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    size_bytes     integer     NOT NULL,
    data           bytea       NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT tidy_photo_size_matches CHECK (size_bytes > 0 AND size_bytes = octet_length(data)),
    CONSTRAINT tidy_photo_one_per_phase UNIQUE (reservation_id, phase)
);

-- 30일 지난 사진 지우기용
CREATE INDEX tidy_photo_created ON tidy_photo (created_at);
