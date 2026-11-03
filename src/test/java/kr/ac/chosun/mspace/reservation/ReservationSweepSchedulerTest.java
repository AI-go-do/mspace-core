package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 스케줄러가 켜져 있으면 정리가 저절로 돈다. 주기를 0.2초로 줄여 5초 안에 노쇼가 되는지만 본다
 * (규칙은 ReservationSweepTest에서). 예약은 DB에 직접 넣는다: 마감이 이미 지난 BOOKED 1건.
 */
@SpringBootTest(properties = {"mspace.reservation.sweep.enabled=true", "mspace.reservation.sweep.interval=PT0.2S"})
@Import(TestcontainersConfiguration.class)
class ReservationSweepSchedulerTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("sweep.enabled=true면 주기마다 저절로 노쇼 처리")
    void runsByItself() throws InterruptedException {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, access_event RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "s" + i + "@chosun.ac.kr").param("s", "2024000" + i).update();
        }
        // 어제 끝난 예약을 BOOKED로 넣는다 (예약 API는 지난 시각을 막으므로 SQL로). 마감은 트리거가 max(시작, 예약한 시각) + 15분으로 채우므로 예약한 시각도 그 전으로 넣는다.
        // 인원 확인 트리거가 커밋 때 돌아서 예약과 참여자를 한 문장(한 트랜잭션)으로 넣는다
        jdbc.sql("""
                WITH r AS (
                    INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, created_at)
                    VALUES (1, 1, date_trunc('hour', now()) - interval '1 day',
                            date_trunc('hour', now()) - interval '1 day' + interval '1 hour', 3, '123456',
                            now() - interval '2 days')
                    RETURNING id)
                INSERT INTO reservation_participant (reservation_id, user_id) SELECT r.id, u FROM r, unnest(ARRAY[1, 2, 3]) u
                """).update();

        String status = "BOOKED";
        for (int i = 0; i < 50 && status.equals("BOOKED"); i++) {
            Thread.sleep(100);
            status = jdbc.sql("SELECT status FROM reservation WHERE id = 1").query(String.class).single();
        }
        assertThat(status).isEqualTo("NO_SHOW");
    }
}
