package kr.ac.chosun.mspace.photo.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.photo.application.CameraPhotoPort;
import kr.ac.chosun.mspace.photo.application.PhotoPort.StoredPhoto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 표는 V28__tidy_photo.sql. 30일 정리는 JdbcPhotoAdapter.deleteUploadedBefore가 같이 한다 */
@Component
class JdbcCameraPhotoAdapter implements CameraPhotoPort {

    private final JdbcClient jdbc;

    JdbcCameraPhotoAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean inRoom(int roomId, long reservationId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reservation WHERE id = :id AND room_id = :room)")
                .param("id", reservationId).param("room", roomId).query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public Optional<Long> lastEnded(int roomId) {
        return jdbc.sql("""
                        SELECT id FROM reservation
                        WHERE room_id = :room
                          AND status IN ('CHECKED_OUT', 'AUTO_CHECKED_OUT', 'AWAY_RELEASED')
                          AND checked_out_at IS NOT NULL
                        ORDER BY checked_out_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("room", roomId).query((rs, n) -> rs.getLong(1)).optional();
    }

    @Override
    public boolean insertIfAbsent(long reservationId, String phase, String deviceId, String contentType, byte[] data,
                                  Instant uploadedAt) {
        return jdbc.sql("""
                        INSERT INTO tidy_photo (reservation_id, phase, device_id, content_type, size_bytes, data, created_at)
                        VALUES (:id, :phase, :device, :type, :size, :data, :at)
                        ON CONFLICT (reservation_id, phase) DO NOTHING
                        """)
                .param("id", reservationId).param("phase", phase).param("device", deviceId)
                .param("type", contentType).param("size", data.length).param("data", data)
                .param("at", Timestamp.from(uploadedAt))
                .update() == 1;
    }

    @Override
    public Optional<StoredPhoto> find(long reservationId, String phase) {
        return jdbc.sql("SELECT content_type, data FROM tidy_photo WHERE reservation_id = :id AND phase = :phase")
                .param("id", reservationId).param("phase", phase)
                .query((rs, n) -> new StoredPhoto(rs.getString("content_type"), rs.getBytes("data")))
                .optional();
    }
}
