-- 사진 체크인·아웃 (FR-RPT-102, 11/5 지영 결정: 사진은 core DB에 저장).
-- V1에서 만든 reservation_photo는 url만 있었다(아직 쓰는 API 없음, 빈 표). 파일 서버 없이 사진 자체를 담는다.
-- 왜 DB: 새 서버·라이브러리·배포 설정 없이 바로 되고, 예약과 한 트랜잭션이라 어긋남이 없다.
-- 대신 크기가 커지므로 1장 최대 2MB(앱에서 검사), 올린 지 30일 지나면 지운다(created_at 인덱스).
-- 나중에 파일·저장 서버로 옮기면 저장 포트(PhotoPort)의 어댑터만 바꾼다.
ALTER TABLE reservation_photo DROP COLUMN url;
ALTER TABLE reservation_photo
    ADD COLUMN content_type varchar(20) NOT NULL,
    ADD COLUMN size_bytes   integer     NOT NULL,
    ADD COLUMN data         bytea       NOT NULL,
    ADD CONSTRAINT photo_content_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    ADD CONSTRAINT photo_size_matches CHECK (size_bytes > 0 AND size_bytes = octet_length(data));

-- 30일 지난 사진 지우기용
CREATE INDEX reservation_photo_created ON reservation_photo (created_at);
