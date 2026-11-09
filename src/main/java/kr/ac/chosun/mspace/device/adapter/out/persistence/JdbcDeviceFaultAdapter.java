package kr.ac.chosun.mspace.device.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.device.application.DeviceFaultPort;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Attempt;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Fault;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 장치 오류 저장 (V23) */
@Component
class JdbcDeviceFaultAdapter implements DeviceFaultPort {

    private final JdbcClient jdbc;

    JdbcDeviceFaultAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> insert(Fault f, Instant receivedAt) {
        // 같은 eventId가 동시에 오면 나중 쪽은 먼저 쪽 커밋을 기다렸다가 DO NOTHING → 0줄 (센서 이벤트와 같음)
        return jdbc.sql("""
                        INSERT INTO device_fault (event_id, room_id, device_id, kind, started_at, ended_at, received_at)
                        VALUES (:eventId, :room, :device, :kind, :start, :end, :received)
                        ON CONFLICT (event_id) DO NOTHING
                        RETURNING id
                        """)
                .param("eventId", f.eventId()).param("room", f.roomId()).param("device", f.deviceId())
                .param("kind", f.kind().name())
                .param("start", Timestamp.from(f.startedAt())).param("end", Timestamp.from(f.endedAt()))
                .param("received", Timestamp.from(receivedAt))
                .query((rs, n) -> rs.getLong(1)).optional();
    }

    @Override
    public boolean insertAttempt(long faultId, int roomId, Attempt a, int openBeforeMinutes) {
        return jdbc.sql("""
                        INSERT INTO device_fault_attempt (fault_id, attempted_at, via, reservation_id)
                        VALUES (:fault, :at, :via,
                                (SELECT r.id FROM reservation r
                                 WHERE r.room_id = :room AND r.access_code = :code AND r.status <> 'CANCELED'
                                   AND :at >= r.start_at - make_interval(mins => :open)
                                   AND :at < least(r.checkin_deadline, r.end_at)
                                 ORDER BY r.start_at LIMIT 1))
                        RETURNING reservation_id IS NOT NULL
                        """)
                .param("fault", faultId).param("at", Timestamp.from(a.at())).param("via", a.via().name())
                .param("room", roomId).param("code", a.code()).param("open", openBeforeMinutes)
                .query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public int attemptsSince(int roomId, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM device_fault_attempt a JOIN device_fault f ON f.id = a.fault_id
                        WHERE f.room_id = :room AND f.received_at > :since
                        """)
                .param("room", roomId).param("since", Timestamp.from(since))
                .query((rs, n) -> rs.getInt(1)).single();
    }

    /** 두 정수 키 (23, 방): 한 정수 키를 쓰는 예약·회원 잠금과 겹치지 않음. 23은 V23(이 기능) 번호 */
    @Override
    public void lockRoomCount(int roomId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(23, :room)").param("room", roomId).query((rs, n) -> 1).list();
    }
}
