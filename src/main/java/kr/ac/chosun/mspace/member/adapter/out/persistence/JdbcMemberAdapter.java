package kr.ac.chosun.mspace.member.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.member.application.MemberPort;
import kr.ac.chosun.mspace.member.application.MemberQueryPort;
import kr.ac.chosun.mspace.member.application.MemberQueryPort.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** users 테이블: 로그인(MemberPort)과 참여자 학번 검색(MemberQueryPort) */
@Component
class JdbcMemberAdapter implements MemberPort, MemberQueryPort {

    private final JdbcClient jdbc;

    JdbcMemberAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> findIdByEmail(String email) {
        return jdbc.sql("SELECT id FROM users WHERE email = :email")
                .param("email", email)
                .query(Long.class)
                .optional();
    }

    @Override
    public long insertVerified(String email, String studentId, String name, Instant verifiedAt) {
        try {
            return jdbc.sql("""
                            INSERT INTO users (email, student_id, name, verified_at)
                            VALUES (:email, :studentId, :name, :verifiedAt)
                            RETURNING id
                            """)
                    .param("email", email)
                    .param("studentId", studentId)
                    .param("name", name)
                    .param("verifiedAt", Timestamp.from(verifiedAt))
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            // users에는 UNIQUE가 둘(email, student_id). PostgreSQL 기본 제약 이름으로 어느 쪽인지 가린다
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("users_student_id_key")) {
                throw new BusinessException(ErrorCode.STUDENT_ID_TAKEN);
            }
            // 같은 메일로 거의 동시에 두 번 가입한 경우의 늦은 쪽
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_SIGNED_UP);
        }
    }

    @Override
    public void markVerifiedIfNot(long memberId, Instant verifiedAt) {
        jdbc.sql("UPDATE users SET verified_at = :verifiedAt WHERE id = :id AND verified_at IS NULL")
                .param("verifiedAt", Timestamp.from(verifiedAt))
                .param("id", memberId)
                .update();
    }

    @Override
    public Optional<VerifiedMember> findVerified(String studentId) {
        // student_id는 UNIQUE라 많아야 한 줄. 인덱스(UNIQUE가 만든 것)로 바로 찾는다
        return jdbc.sql("SELECT id, name FROM users WHERE student_id = :sid AND verified_at IS NOT NULL")
                .param("sid", studentId)
                .query((rs, n) -> new VerifiedMember(rs.getLong("id"), rs.getString("name")))
                .optional();
    }

    @Override
    public Optional<Profile> findProfile(long memberId) {
        return jdbc.sql("SELECT student_id, name FROM users WHERE id = :id")
                .param("id", memberId)
                .query((rs, n) -> new Profile(rs.getString("student_id"), rs.getString("name")))
                .optional();
    }
}
