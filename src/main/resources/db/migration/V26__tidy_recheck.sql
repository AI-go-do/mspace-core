-- 두고 간 물건 다시 확인 (FR-DEV-105, 11/10 10차 회의 + 11/10 지영·수연)
-- 1) 종류 구분 없이 "두고 감" 하나로: result에 LEFT_ITEMS. 쓰레기도 여기에 들어간다
--    예전 NEEDS_CLEANING(바로 어질러짐 신고)은 지난 기록 때문에 남겨 두고 새로 받지 않는다
-- 2) 예약마다 FIRST(퇴실 직후) 1번 + RECHECK(2시간 뒤 다시 확인) 1번
-- 3) 물건마다 확신도(0~1). items[i]의 확신도가 confidences[i]. 예전 줄은 NULL
-- 4) 신고는 RECHECK에 물건이 남아 경고할 때만 (core가 사람 없이 사실로 정함). 예전 NEEDS_CLEANING 줄은 그대로
ALTER TABLE tidy_check DROP CONSTRAINT tidy_check_reservation_id_key;
ALTER TABLE tidy_check DROP CONSTRAINT tidy_check_result_check;
ALTER TABLE tidy_check DROP CONSTRAINT cleaning_has_report;

ALTER TABLE tidy_check
    ADD COLUMN phase       varchar(8) NOT NULL DEFAULT 'FIRST' CHECK (phase IN ('FIRST', 'RECHECK')),
    ADD COLUMN confidences real[],
    ADD CONSTRAINT tidy_check_result_check CHECK (result IN ('CLEAN', 'LEFT_ITEMS', 'NEEDS_CLEANING')),
    ADD CONSTRAINT tidy_check_one_per_phase UNIQUE (reservation_id, phase),
    ADD CONSTRAINT tidy_check_confidence_pairs CHECK (
        confidences IS NULL
        OR (cardinality(confidences) = cardinality(items)
            AND 0 <= ALL (confidences) AND 1 >= ALL (confidences))),
    ADD CONSTRAINT tidy_check_left_has_items CHECK (result <> 'LEFT_ITEMS' OR cardinality(items) > 0),
    ADD CONSTRAINT tidy_check_report_kind CHECK (
        CASE WHEN report_id IS NULL THEN result <> 'NEEDS_CLEANING'
             ELSE (phase = 'FIRST' AND result = 'NEEDS_CLEANING') OR (phase = 'RECHECK' AND result = 'LEFT_ITEMS')
        END);

ALTER TABLE tidy_check ALTER COLUMN phase DROP DEFAULT;
