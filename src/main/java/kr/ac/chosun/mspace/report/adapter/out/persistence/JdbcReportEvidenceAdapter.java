package kr.ac.chosun.mspace.report.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.report.application.ReportEvidencePort;
import kr.ac.chosun.mspace.report.application.ReportEvidenceUseCase.Tidy;
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
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional();
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

    @Override
    public Optional<Tidy> tidyCheck(long reservationId) {
        // 다음 팀 신고의 근거는 퇴실 직후(FIRST) 모습. RECHECK(2시간 뒤)는 두고 간 물건 경고용
        return jdbc.sql("""
                        SELECT result, items, left_items, observed_at FROM tidy_check
                        WHERE reservation_id = :id AND phase = 'FIRST'
                        """)
                .param("id", reservationId)
                .query((rs, n) -> new Tidy(rs.getString(1), List.of((String[]) rs.getArray(2).getArray()),
                        List.of((String[]) rs.getArray(3).getArray()), rs.getTimestamp(4).toInstant()))
                .optional();
    }
}
