-- 회원 권한 (명세 Q-06: role = ADMIN, 시연은 팀원 계정, 실제는 교학팀)
-- 처음 쓰는 곳: 예약 건수 API(관리자만). 나중에 신고 판정·관리자 화면(FR-RPT-103, FR-WEB-101)도 같은 칸을 본다.
-- 관리자 지정은 화면 없이 SQL로: UPDATE users SET role = 'ADMIN' WHERE email = '...';
ALTER TABLE users
    ADD COLUMN role varchar(10) NOT NULL DEFAULT 'USER'
        CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
