package kr.ac.chosun.mspace.appeal;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.appeal.application.AppealReviewUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** Q-24 ③: 판단기가 "유지"라도 확신도가 mspace.appeal.min-confidence보다 낮으면 철회. 기록에는 원래 확신도 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.appeal.min-confidence=0.9"})
@Import(TestcontainersConfiguration.class)
class AppealLowConfidenceTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AppealReviewUseCase reviews;

    @Autowired
    TransactionOperations tx;

    @Test
    @DisplayName("카메라 확신도 0.85로 판단기는 유지라고 해도 철회 기준 0.9보다 낮아 철회한다")
    void lowConfidenceWithdraws() {
        jdbc.sql("""
                TRUNCATE warning_appeal, tidy_check, notification, report_suspension, user_suspension, user_warning, report,
                         reservation_photo, reservation_participant, reservation, users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "b" + i + "@chosun.ac.kr").param("s", "202400" + (80 + i)).update();
        }
        long rid = tx.execute(st -> {
            long id = jdbc.sql("""
                    INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                             checked_in_at, checked_out_at)
                    SELECT 1, 1, t - interval '5 hours', t - interval '4 hours', 3, '000001', 'CHECKED_OUT',
                           t - interval '5 hours', t - interval '4 hours'
                    FROM (SELECT date_trunc('hour', now()) AS t) x
                    RETURNING id
                    """).query(Long.class).single();
            for (long uid = 1; uid <= 3; uid++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", uid).param("owner", uid == 1).update();
            }
            return id;
        });
        long report = jdbc.sql("""
                INSERT INTO report (room_id, source, target_reservation_id, category, verdict, judged_at)
                VALUES (1, 'CAMERA', :rid, 'MESSY', 'CONFIRMED', now()) RETURNING id
                """).param("rid", rid).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, phase, result, items, confidences,
                                        left_items, report_id, observed_at, received_at)
                VALUES ('ev-low', :rid, 1, 'cam-1', 'RECHECK', 'LEFT_ITEMS', '{cup}', '{0.85}', '{}', :rep, now(), now())
                """).param("rid", rid).param("rep", report).update();
        long warning = jdbc.sql("""
                INSERT INTO user_warning (user_id, reason, report_id, reservation_id, created_at)
                VALUES (1, 'VIOLATION', :rep, :rid, now() - interval '1 hour') RETURNING id
                """).param("rep", report).param("rid", rid).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO warning_appeal (warning_id, user_id, text, evidence, created_at)
                VALUES (:w, 1, '두고 간 물건은 바로 찾아갔어요.', '{}', now())
                """).param("w", warning).update();

        reviews.reviewPending();

        var row = jdbc.sql("SELECT status, confidence, answer FROM warning_appeal")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getFloat(2), rs.getString(3)}).single();
        assertThat(row[0]).isEqualTo("WITHDRAWN");
        assertThat((float) row[1]).isEqualTo(0.85f);
        assertThat((String) row[2]).contains("확인할 수 없으면 벌칙을 주지 않아요");
        assertThat(jdbc.sql("SELECT withdrawn_at IS NOT NULL FROM user_warning").query(Boolean.class).single()).isTrue();
    }
}
