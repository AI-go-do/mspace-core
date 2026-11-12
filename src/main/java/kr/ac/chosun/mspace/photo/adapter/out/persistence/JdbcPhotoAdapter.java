package kr.ac.chosun.mspace.photo.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.photo.application.PhotoKind;
import kr.ac.chosun.mspace.photo.application.PhotoPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 표는 V1 reservation_photo + V14(사진 자체를 bytea로). 트랜잭션은 서비스가 연다 */
@Component
class JdbcPhotoAdapter implements PhotoPort {

    private final JdbcClient jdbc;

    JdbcPhotoAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReservationView> findForMember(long reservationId, long userId) {
        // reservation_participant에는 대표자도 들어 있다(is_owner)
        return jdbc.sql("""
                SELECT r.status, r.checked_out_at
                FROM reservation r
                JOIN reservation_participant p ON p.reservation_id = r.id AND p.user_id = :user
                WHERE r.id = :id
                """)
                .param("id", reservationId).param("user", userId)
                .query((rs, n) -> {
                    Timestamp out = rs.getTimestamp("checked_out_at");
                    return new ReservationView(rs.getString("status"), out == null ? null : out.toInstant());
                })
                .optional();
    }

    @Override
    public boolean isMember(long reservationId, long userId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM reservation_participant WHERE reservation_id = :id AND user_id = :user)
                """)
                .param("id", reservationId).param("user", userId).query(Boolean.class).single();
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :user AND role = 'ADMIN')")
                .param("user", userId).query(Boolean.class).single();
    }

    @Override
    public boolean insertIfAbsent(NewPhoto p) {
        // 같은 예약·종류를 동시에 올리면 나중 쪽은 먼저 쪽 커밋을 기다렸다가 DO NOTHING → 0줄
        return jdbc.sql("""
                INSERT INTO reservation_photo (reservation_id, kind, uploaded_by, content_type, size_bytes, data, created_at)
                VALUES (:id, :kind, :user, :type, :size, :data, :at)
                ON CONFLICT (reservation_id, kind) DO NOTHING
                """)
                .param("id", p.reservationId()).param("kind", p.kind().name()).param("user", p.uploadedBy())
                .param("type", p.contentType()).param("size", p.data().length).param("data", p.data())
                .param("at", Timestamp.from(p.uploadedAt()))
                .update() == 1;
    }

    @Override
    public Optional<StoredPhoto> find(long reservationId, PhotoKind kind) {
        return jdbc.sql("SELECT content_type, data FROM reservation_photo WHERE reservation_id = :id AND kind = :kind")
                .param("id", reservationId).param("kind", kind.name())
                .query((rs, n) -> new StoredPhoto(rs.getString("content_type"), rs.getBytes("data")))
                .optional();
    }

    @Override
    public int deleteUploadedBefore(Instant cutoff) {
        // 학생 사진과 방 카메라 사진(V28 tidy_photo)을 같은 30일로 지운다
        int students = jdbc.sql("DELETE FROM reservation_photo WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff)).update();
        int camera = jdbc.sql("DELETE FROM tidy_photo WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff)).update();
        return students + camera;
    }
}
