-- 사진 체크인·아웃 (FR-RPT-102, 11/5 지영 결정: 사진은 core DB에 저장).
-- V1에서 만든 reservation_photo는 url만 있었다(아직 쓰는 API 없음, 빈 표). 파일 서버 없이 사진 자체를 담는다.
-- 왜 DB: 새 서버·라이브러리·배포 설정 없이 바로 되고, 예약과 한 트랜잭션이라 어긋남이 없다.
-- 대신 크기가 커지므로 1장 최대 2MB(앱에서 검사), 올린 지 30일 지나면 지운다(created_at 인덱스).
-- 나중에 파일·저장 서버로 옮기면 저장 포트(PhotoPort)의 어댑터만 바꾼다.
-- 이미 행이 있으면 사진 자체가 없어 새 칸을 채울 수 없다. 지우거나 옮길지는 사람이 정해야 하므로
-- 데이터를 건드리지 않고 멈춘다 (V13과 같은 방식). 쓰는 API가 없어서 실제로는 빈 표여야 한다.
DO $$
DECLARE
    n bigint;
BEGIN
    SELECT count(*) INTO n FROM reservation_photo;
    IF n > 0 THEN
        RAISE EXCEPTION 'V14: reservation_photo에 기존 행 %건이 있어 멈춤. 지울지 옮길지 정한 뒤 다시 실행 (docs/결정.md 14)', n;
    END IF;
END $$;

ALTER TABLE reservation_photo DROP COLUMN url;
ALTER TABLE reservation_photo
    ADD COLUMN content_type varchar(20) NOT NULL,
    ADD COLUMN size_bytes   integer     NOT NULL,
    ADD COLUMN data         bytea       NOT NULL,
    ADD CONSTRAINT photo_content_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    ADD CONSTRAINT photo_size_matches CHECK (size_bytes > 0 AND size_bytes = octet_length(data));

-- 30일 지난 사진 지우기용
CREATE INDEX reservation_photo_created ON reservation_photo (created_at);
