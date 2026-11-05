package kr.ac.chosun.mspace.notification.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.notification.application.NotificationQueryPort;
import kr.ac.chosun.mspace.notification.application.NotificationWritePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcNotificationQueryAdapter implements NotificationQueryPort, NotificationWritePort {

    private final JdbcClient jdbc;

    JdbcNotificationQueryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Notification> findRecent(long userId, int limit) {
        // 인덱스 notification_user_recent (user_id, created_at DESC)를 그대로 탄다
        return jdbc.sql("""
                SELECT id, user_id, reservation_id, kind, title, body, created_at, read_at
                FROM notification WHERE user_id = :user
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("user", userId).param("limit", limit)
                .query((rs, n) -> {
                    Timestamp read = rs.getTimestamp("read_at");
                    return new Notification(rs.getLong("id"), rs.getLong("user_id"),
                            rs.getObject("reservation_id", Long.class), rs.getString("kind"), rs.getString("title"),
                            rs.getString("body"), rs.getTimestamp("created_at").toInstant(),
                            read == null ? null : read.toInstant());
                })
                .list();
    }

    @Override
    @Transactional
    public List<Notification> saveAll(List<NewNotification> items, Instant now) {
        List<Notification> saved = new ArrayList<>();
        for (NewNotification n : items) {
            long id = jdbc.sql("""
                    INSERT INTO notification (user_id, reservation_id, kind, title, body, created_at)
                    VALUES (:user, :reservation, :kind, :title, :body, :now)
                    RETURNING id
                    """)
                    .param("user", n.userId()).param("reservation", n.reservationId()).param("kind", n.kind())
                    .param("title", n.title()).param("body", n.body()).param("now", Timestamp.from(now))
                    .query(Long.class).single();
            saved.add(new Notification(id, n.userId(), n.reservationId(), n.kind(), n.title(), n.body(), now, null));
        }
        return saved;
    }
}
