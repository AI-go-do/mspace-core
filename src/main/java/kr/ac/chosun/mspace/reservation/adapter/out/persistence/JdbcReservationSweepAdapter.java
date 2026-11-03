package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import kr.ac.chosun.mspace.reservation.application.ReservationSweepPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL은 V1__init_schema.sql 끝 "6. 스케줄러(1분 주기)"와 같다 */
@Component
class JdbcReservationSweepAdapter implements ReservationSweepPort {

    private final JdbcClient jdbc;

    JdbcReservationSweepAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int markNoShows(Instant now) {
        // 같은 줄을 키패드 체크인(BOOKED → IN_USE)이 동시에 바꾸면 줄 잠금으로 한쪽이 기다렸다가
        // WHERE를 다시 확인한다. 그래서 둘 중 하나만 바뀐다
        return jdbc.sql("""
                        UPDATE reservation SET status = 'NO_SHOW'
                        WHERE status = 'BOOKED' AND checkin_deadline < :now
                        """)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public int autoCheckout(Instant now) {
        // 퇴실 시각은 정리가 돈 시각이 아니라 끝나는 시각 (최대 1분 늦게 돌아도 기록은 정확하게)
        return jdbc.sql("""
                        UPDATE reservation SET status = 'AUTO_CHECKED_OUT', checked_out_at = end_at
                        WHERE status = 'IN_USE' AND end_at <= :now
                        """)
                .param("now", Timestamp.from(now))
                .update();
    }
}
