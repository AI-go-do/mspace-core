package kr.ac.chosun.mspace.report.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.report.application.ReportEvidencePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 증거 사진 조회. reservation_photo의 (reservation_id, kind) 유니크 인덱스로 한 줄만 읽는다 */
@Component
class JdbcReportEvidenceAdapter implements ReportEvidencePort {

    private final JdbcClient jdbc;

    JdbcReportEvidenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> photoUploadedAt(long reservationId, String kind) {
        return jdbc.sql("SELECT created_at FROM reservation_photo WHERE reservation_id = :id AND kind = :kind")
                .param("id", reservationId).param("kind", kind)
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    @Override
    public Optional<Times> times(long reservationId) {
        return jdbc.sql("SELECT checked_in_at, checked_out_at FROM reservation WHERE id = :id")
                .param("id", reservationId)
                .query((rs, n) -> {
                    Timestamp in = rs.getTimestamp(1);
                    Timestamp out = rs.getTimestamp(2);
                    return new Times(in == null ? null : in.toInstant(), out == null ? null : out.toInstant());
                })
                .optional();
    }
}
