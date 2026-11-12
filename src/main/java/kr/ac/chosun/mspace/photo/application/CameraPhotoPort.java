package kr.ac.chosun.mspace.photo.application;

import java.time.Instant;
import java.util.Optional;

/** 표는 V28__tidy_photo.sql. 구현: JdbcCameraPhotoAdapter */
public interface CameraPhotoPort {

    /** 이 방에 그 예약이 있는지 */
    boolean inRoom(int roomId, long reservationId);

    /** 이 방에서 마지막으로 퇴실한 예약 id (정리 확인 GET /current의 lastEnded와 같은 기준) */
    Optional<Long> lastEnded(int roomId);

    /** 같은 예약·단계가 이미 있으면 저장하지 않는다. 저장했으면 true */
    boolean insertIfAbsent(long reservationId, String phase, String deviceId, String contentType, byte[] data,
                           Instant uploadedAt);

    Optional<PhotoPort.StoredPhoto> find(long reservationId, String phase);
}
