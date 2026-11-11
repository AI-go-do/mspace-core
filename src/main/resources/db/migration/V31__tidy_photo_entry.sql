-- 정리 확인 사진에 입실 직전 빈 방(ENTRY) 단계 추가 (docs Q-27 11/11 수연 결정, v0.4.39)
-- 사람이 처음 보이기 직전 3초 동안 0명일 때 찍은 컬러 사진. 젯슨은 퇴실 뒤 FIRST 결과를 보낸 다음 같이 올린다.
-- 관리자 화면이 입실 전(ENTRY)·퇴실 뒤(FIRST·RECHECK)를 나란히 본다
ALTER TABLE tidy_photo DROP CONSTRAINT tidy_photo_phase_check;
ALTER TABLE tidy_photo ADD CONSTRAINT tidy_photo_phase_check CHECK (phase IN ('ENTRY', 'FIRST', 'RECHECK'));
