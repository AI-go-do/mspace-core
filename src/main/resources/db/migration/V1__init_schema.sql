-- M-SPACE 예약 스키마 V1 (PostgreSQL 16)
-- 테이블·컬럼·상태 이름은 요구사항 명세 v0.4(7.4 데이터) 기준. 제약·트리거는 검증한 schema-v2.sql 그대로
-- 근거: 예약 규칙 v2 문서 https://claude.ai/artifact/YRBrmtUaA2YP2yVY2BSaui
-- DB가 직접 막는 것: 같은 방 시간 겹침, 대표자당 끝나지 않은 예약 1건,
--   같은 사람 같은 시간 두 방, 1회 최대 2시간·30분 단위, 방별 인원, 상태 전이 순서
-- 서버가 확인할 것은 파일 끝 주석 참고

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 회원: 학교메일로만 가입
CREATE TABLE users (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email             varchar(100) NOT NULL UNIQUE
                      CHECK (email ~* '^[^@\s]+@chosun\.ac\.kr$'),
    student_id        varchar(20)  NOT NULL UNIQUE,
    name              varchar(50)  NOT NULL,
    verified_at       timestamptz,
    rules_agreed_version integer,  -- 동의한 이용 수칙 버전 (FR-WEB-001)
    created_at        timestamptz  NOT NULL DEFAULT now()
);

-- 방: 방마다 이용 인원 범위가 다름 (1~4번 3~5명, 5번 5~8명)
CREATE TABLE rooms (
    id         smallint PRIMARY KEY,
    name       varchar(30) NOT NULL UNIQUE,
    min_people smallint    NOT NULL,
    max_people smallint    NOT NULL,
    is_active  boolean     NOT NULL DEFAULT true,  -- 수리 중 등 사용 불가면 false (FR-ROOM-001)
    CHECK (min_people >= 1 AND max_people >= min_people)
);

-- 옆 방 관계 (신고 권한 판단용). 양방향 모두 넣는다
CREATE TABLE room_adjacency (
    room_id          smallint NOT NULL REFERENCES rooms(id),
    adjacent_room_id smallint NOT NULL REFERENCES rooms(id),
    PRIMARY KEY (room_id, adjacent_room_id),
    CHECK (room_id <> adjacent_room_id)
);

-- 예약
CREATE TABLE reservation (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id       smallint    NOT NULL REFERENCES rooms(id),
    owner_id      bigint      NOT NULL REFERENCES users(id),
    start_at      timestamptz NOT NULL,
    end_at        timestamptz NOT NULL,
    headcount     smallint    NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'BOOKED'
                  CHECK (status IN ('BOOKED', 'IN_USE', 'CHECKED_OUT', 'AUTO_CHECKED_OUT', 'NO_SHOW', 'CANCELED')),
    is_instant    boolean     NOT NULL DEFAULT false,  -- 즉시 시작(빈 방 바로 사용)
    access_code   char(6)     NOT NULL CHECK (access_code ~ '^[0-9]{6}$'),  -- 키패드 6자리, 대표자에게만 보여 줌
    checkin_deadline timestamptz NOT NULL,  -- 노쇼 기준: max(시작 시각, 예약한 시각) + 15분 (트리거가 채움)
    checked_in_at timestamptz,
    checked_out_at   timestamptz,
    canceled_at   timestamptz,
    created_via   varchar(10) NOT NULL DEFAULT 'WEB'
                  CHECK (created_via IN ('WEB', 'AGENT', 'DEVICE')),  -- 누가 만들었나 (NFR-COM-008)
    created_at    timestamptz NOT NULL DEFAULT now(),

    -- 끝은 항상 30분 단위 (KST는 UTC+9라 UTC 기준 30분 단위와 같음)
    -- 미리 예약: 시작도 30분 단위, 최대 2시간
    -- 즉시 시작: 시작은 누른 시각 그대로, 끝은 (시작+2시간)을 30분 칸으로 올림 (2:17 → 최대 4:30)
    CONSTRAINT valid_length CHECK (
        start_at < end_at
        AND extract(epoch FROM end_at)::bigint % 1800 = 0
        AND (
            (NOT is_instant AND extract(epoch FROM start_at)::bigint % 1800 = 0
                            AND end_at - start_at <= interval '2 hours')
         OR (is_instant AND end_at - start_at < interval '2 hours 30 minutes')
        )
    ),
    CONSTRAINT checked_out_has_time CHECK (
        (status IN ('CHECKED_OUT', 'AUTO_CHECKED_OUT')) = (checked_out_at IS NOT NULL)
    ),
    CONSTRAINT canceled_has_time CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL)),
    CONSTRAINT in_use_has_checkin CHECK (
        status NOT IN ('IN_USE', 'CHECKED_OUT', 'AUTO_CHECKED_OUT') OR checked_in_at IS NOT NULL
    ),
    -- 같은 방 시간 겹침 금지. 퇴실·노쇼가 되면 빠지므로 방이 바로 풀림
    CONSTRAINT no_room_overlap EXCLUDE USING gist (
        room_id WITH =, tstzrange(start_at, end_at) WITH &&
    ) WHERE (status IN ('BOOKED', 'IN_USE'))
);

-- 대표자당 끝나지 않은 예약 동시 1건
CREATE UNIQUE INDEX one_active_per_owner
    ON reservation (owner_id) WHERE status IN ('BOOKED', 'IN_USE');

CREATE INDEX reservation_room_time ON reservation (room_id, start_at);

-- 이용자(대표자 포함). 학번으로 입력받아 가입한 회원의 id로 저장
-- start_at, end_at, active는 예약에서 복사해 두고 트리거로 맞춘다 (EXCLUDE는 한 테이블 안에서만 걸 수 있어서)
CREATE TABLE reservation_participant (
    reservation_id bigint    NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    user_id        bigint    NOT NULL REFERENCES users(id),
    is_owner       boolean     NOT NULL DEFAULT false,
    start_at       timestamptz NOT NULL,
    end_at         timestamptz NOT NULL,
    active         boolean     NOT NULL DEFAULT true,
    PRIMARY KEY (reservation_id, user_id),
    -- 같은 사람이 같은 시간에 두 방 금지
    CONSTRAINT no_person_overlap EXCLUDE USING gist (
        user_id WITH =, tstzrange(start_at, end_at) WITH &&
    ) WHERE (active)
);

CREATE INDEX participant_user ON reservation_participant (user_id);

-- 사진 체크인·체크아웃 (선택)
CREATE TABLE reservation_photo (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id bigint       NOT NULL REFERENCES reservation(id),
    kind           varchar(10)  NOT NULL CHECK (kind IN ('CHECK_IN', 'CHECK_OUT')),
    url            varchar(500) NOT NULL,
    uploaded_by    bigint       NOT NULL REFERENCES users(id),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (reservation_id, kind)
);

-- 신고: 그 방 예약자 또는 옆 방 예약자만 (권한은 서버에서 확인)
CREATE TABLE report (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id                 smallint     NOT NULL REFERENCES rooms(id),         -- 신고 대상 방
    reporter_id             bigint       NOT NULL REFERENCES users(id),
    reporter_reservation_id bigint       NOT NULL REFERENCES reservation(id),  -- 신고 권한 근거 예약
    category                varchar(12)  NOT NULL CHECK (category IN ('BROKEN', 'WINDOW_OPEN', 'MESSY', 'NOISE')),
    content                 text,
    photo_url               varchar(500),
    created_at              timestamptz  NOT NULL DEFAULT now(),
    -- 관리자 판정: OPEN(대기) / CONFIRMED(사실) / UNVERIFIED(확인 불가, 벌칙 없음) / FALSE(허위, 경고)
    verdict                 varchar(12)  NOT NULL DEFAULT 'OPEN'
                            CHECK (verdict IN ('OPEN', 'CONFIRMED', 'UNVERIFIED', 'FALSE')),
    judged_by               bigint       REFERENCES users(id),
    judged_at               timestamptz,
    -- 소음은 증명할 수 없으므로 허위 판정 대상이 아님 (비품 고장·창문 열림·어질러짐만)
    CONSTRAINT noise_not_false CHECK (NOT (category = 'NOISE' AND verdict = 'FALSE')),
    CONSTRAINT judged_has_time CHECK ((verdict = 'OPEN') = (judged_at IS NULL))
);

-- 알림 발송 기록: 예약·사람·종류별로 한 번만 보냄 (스케줄러가 두 번 돌아도 중복 발송 없음)
CREATE TABLE notification_log (
    reservation_id bigint      NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    user_id        bigint      NOT NULL REFERENCES users(id),
    kind           varchar(20) NOT NULL CHECK (kind IN ('START_SOON', 'END_SOON')),
    sent_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (reservation_id, user_id, kind)
);

-- 트리거 0: 체크인 마감 시각 계산
--   미리 예약: 시작 + 15분 (2시 시작 → 2:15) / 즉시 시작: 누른 시각 + 10분 (2:17 → 2:27)
CREATE FUNCTION reservation_set_deadline() RETURNS trigger AS $$
BEGIN
    IF NEW.is_instant THEN
        NEW.checkin_deadline := NEW.start_at + interval '10 minutes';
    ELSE
        NEW.checkin_deadline := greatest(NEW.start_at, NEW.created_at) + interval '15 minutes';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reservation_set_deadline
    BEFORE INSERT ON reservation
    FOR EACH ROW EXECUTE FUNCTION reservation_set_deadline();

-- 트리거 1: 이용자 행에 예약의 시간·상태를 복사
CREATE FUNCTION participant_copy_times() RETURNS trigger AS $$
DECLARE r reservation%ROWTYPE;
BEGIN
    SELECT * INTO r FROM reservation WHERE id = NEW.reservation_id;
    -- 학생 인증(학교메일 인증)을 마친 회원만 이용자 목록에 들어갈 수 있음
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = NEW.user_id AND verified_at IS NOT NULL) THEN
        RAISE EXCEPTION '학생 인증을 마친 회원만 참여자로 넣을 수 있습니다' USING ERRCODE = 'check_violation';
    END IF;
    NEW.start_at := r.start_at;
    NEW.end_at   := r.end_at;
    NEW.active   := r.status IN ('BOOKED', 'IN_USE');
    NEW.is_owner := (NEW.user_id = r.owner_id);
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER participant_copy_times
    BEFORE INSERT OR UPDATE ON reservation_participant
    FOR EACH ROW EXECUTE FUNCTION participant_copy_times();

-- 트리거 2: 예약 상태·시간이 바뀌면 이용자 행도 맞춤 (퇴실·노쇼 → 이용자도 바로 풀림)
CREATE FUNCTION reservation_sync_participants() RETURNS trigger AS $$
BEGIN
    UPDATE reservation_participant
       SET start_at = NEW.start_at,
           end_at = NEW.end_at,
           active = NEW.status IN ('BOOKED', 'IN_USE')
     WHERE reservation_id = NEW.id;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reservation_sync_participants
    AFTER UPDATE OF status, start_at, end_at ON reservation
    FOR EACH ROW EXECUTE FUNCTION reservation_sync_participants();

-- 트리거 3: 상태는 정해진 순서로만 바뀜
--   BOOKED → IN_USE(체크인) / NO_SHOW / CANCELED(시작 전에만),  IN_USE → CHECKED_OUT(퇴실) / AUTO_CHECKED_OUT(자동 퇴실)
CREATE FUNCTION reservation_status_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.status = OLD.status THEN RETURN NEW; END IF;
    IF OLD.status = 'BOOKED' AND NEW.status = 'CANCELED' AND now() >= OLD.start_at THEN
        RAISE EXCEPTION '시작한 예약은 취소할 수 없습니다' USING ERRCODE = 'check_violation';
    END IF;
    IF (OLD.status, NEW.status) IN (('BOOKED', 'IN_USE'), ('BOOKED', 'NO_SHOW'), ('BOOKED', 'CANCELED'), ('IN_USE', 'CHECKED_OUT'), ('IN_USE', 'AUTO_CHECKED_OUT')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '허용되지 않는 상태 변경: % → %', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reservation_status_guard
    BEFORE UPDATE OF status ON reservation
    FOR EACH ROW EXECUTE FUNCTION reservation_status_guard();

-- 트리거 4: 커밋 시점에 인원 확인
--   예약 인원 = 이용자 수, 방의 최소·최대 인원 안, 대표자도 이용자에 포함
CREATE FUNCTION check_people(rid bigint) RETURNS void AS $$
DECLARE
    r   reservation%ROWTYPE;
    rm  rooms%ROWTYPE;
    cnt int;
    has_owner boolean;
BEGIN
    SELECT * INTO r FROM reservation WHERE id = rid;
    IF NOT FOUND THEN RETURN; END IF;  -- 예약이 지워진 경우
    SELECT * INTO rm FROM rooms WHERE id = r.room_id;
    SELECT count(*), bool_or(user_id = r.owner_id) INTO cnt, has_owner
      FROM reservation_participant WHERE reservation_id = rid;

    IF r.headcount < rm.min_people OR r.headcount > rm.max_people THEN
        RAISE EXCEPTION '% 이용 인원은 %~%명입니다 (입력: %명)', rm.name, rm.min_people, rm.max_people, r.headcount
            USING ERRCODE = 'check_violation';
    END IF;
    IF cnt <> r.headcount THEN
        RAISE EXCEPTION '예약 인원(%명)과 입력한 학번 수(%명)가 다릅니다', r.headcount, cnt
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT COALESCE(has_owner, false) THEN
        RAISE EXCEPTION '대표자도 이용자 목록에 있어야 합니다' USING ERRCODE = 'check_violation';
    END IF;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION reservation_check_people() RETURNS trigger AS $$
BEGIN
    PERFORM check_people(NEW.id);
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION participant_check_people() RETURNS trigger AS $$
BEGIN
    PERFORM check_people(CASE WHEN TG_OP = 'DELETE' THEN OLD.reservation_id ELSE NEW.reservation_id END);
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER reservation_check_people
    AFTER INSERT OR UPDATE OF headcount, room_id ON reservation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION reservation_check_people();

CREATE CONSTRAINT TRIGGER participant_check_people
    AFTER INSERT OR DELETE ON reservation_participant
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION participant_check_people();

-- 방 초기 데이터
INSERT INTO rooms (id, name, min_people, max_people) VALUES
    (1, '1번 방', 3, 5),
    (2, '2번 방', 3, 5),
    (3, '3번 방', 3, 5),
    (4, '4번 방', 3, 5),
    (5, '5번 방', 5, 8);
-- 옆 방 관계는 실제 배치를 확인한 뒤 넣는다 (예: 1-2, 2-3 …)

/* 서버(Spring)에서 확인할 것
 0. 날짜 계산(7일, 자정, 30분 칸)은 모두 Asia/Seoul 기준. 서버·DB 시간대가 UTC면 오전 9시 전 예약이 하루 밀리는 버그가 생김
    (spring.jackson.time-zone, hibernate.jdbc.time_zone, DB 세션 timezone 확인)
 1. 예약 가능 범위: start_at이 지금부터 7일 안 (now()가 들어가 CHECK로 못 막음)
 1-1. 즉시 시작: is_instant=true, start_at=now(), end_at은 사용자가 고른 30분 칸(최대 시작+2시간 올림, 다음 예약 전까지).
      10분 안에 키패드 입력 없으면 노쇼. 일찍 퇴실하면 남은 자투리는 다른 사람 즉시 시작, 다음 30분 칸부터 미리 예약 가능
      미리 예약의 start_at은 지금 이후여야 함
 2. 자정 넘김: 허용. start_at이 7일 안이면 마지막 날도 OK (끝이 8일째 새벽이어도 됨)
 3. 참여자 추가: 학번으로 회원 검색 → 학생 인증한 회원만 결과에 표시 → 고른 사람의 user_id로 저장
    (검색 결과에는 이름 일부만 보여 주기, 예: 김*영. DB 트리거도 인증 안 된 회원을 한 번 더 막음)
 4. 예약 1건 = 한 트랜잭션에서 reservation + reservation_participant 저장
    (인원 확인 트리거가 커밋 때 돌기 때문)
 5. 상태 변경은 조건부 UPDATE로. 예)
      체크인: UPDATE reservation SET status='IN_USE', checked_in_at=now()
              WHERE id=? AND status='BOOKED' AND access_code=? AND now() >= start_at - interval '10 minutes' AND now() <= checkin_deadline
      퇴실:   UPDATE reservation SET status='CHECKED_OUT', checked_out_at=now()
              WHERE id=? AND owner_id=? AND status='IN_USE'
      취소:   UPDATE reservation SET status='CANCELED', canceled_at=now()
              WHERE id=? AND owner_id=? AND status='BOOKED' AND now() < start_at
    → 영향받은 행이 0이면 "이미 처리됨"으로 응답
 6. 스케줄러(1분 주기):
      노쇼:     status='BOOKED' AND now() > checkin_deadline → NO_SHOW
      자동 퇴실: status='IN_USE' AND now() >= end_at → AUTO_CHECKED_OUT, checked_out_at=end_at
 7. 키패드 번호(6자리 난수) 발급, 대표자 화면에만 노출
 8. 소음 신고는 "정말 소란인가요?" 체크박스를 체크해야 접수. 허위 판정(FALSE)은 관리자만, 비품 고장·창문 열림·어질러짐만.
 8-1. 신고 권한: 신고자가 그 방 또는 옆 방(room_adjacency)의 IN_USE 예약 이용자인지
 9. 알림 (스케줄러 1분 주기, 대표자+참여자 전원):
      시작 10분 전: status='BOOKED' AND start_at - now() <= interval '10 min' AND start_at > now()
      종료 10분 전: status='IN_USE' AND end_at - now() <= interval '10 min' AND end_at > now()
      INSERT INTO notification_log ... ON CONFLICT DO NOTHING RETURNING → 새로 들어간 행만 발송
 10. DB 오류 → 사용자 메시지 변환
      no_room_overlap      → "이미 예약된 시간입니다"
      one_active_per_owner → "끝나지 않은 예약이 이미 있습니다"
      no_person_overlap    → "같은 시간에 다른 방 예약에 들어가 있는 사람이 있습니다"
*/
