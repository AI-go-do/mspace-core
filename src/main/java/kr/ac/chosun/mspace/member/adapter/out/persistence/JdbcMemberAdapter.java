package kr.ac.chosun.mspace.member.adapter.out.persistence;

import java.util.Optional;
import kr.ac.chosun.mspace.member.application.MemberQueryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcMemberAdapter implements MemberQueryPort {

    private final JdbcClient jdbc;

    JdbcMemberAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findVerifiedName(String studentId) {
        // student_id는 UNIQUE라 많아야 한 줄. 인덱스(UNIQUE가 만든 것)로 바로 찾는다
        return jdbc.sql("SELECT name FROM users WHERE student_id = :sid AND verified_at IS NOT NULL")
                .param("sid", studentId)
                .query(String.class)
                .optional();
    }
}
