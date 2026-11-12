package kr.ac.chosun.mspace.photo.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.PhotoPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방 카메라 사진 저장. 크기(2MB)·형식(파일 앞부분)·보관(30일)은 학생 사진(PhotoService)과 같은 규칙.
 * 장치 토큰은 방마다 공용이라, 지난 예약에 사진을 붙이지 못하게 이 방의 마지막 퇴실 예약만 받는다(정리 확인과 같음).
 */
@Service
class CameraPhotoService implements CameraPhotoUseCase {

    /** ENTRY = 입실 직전 빈 방(11/11 수연, Q-27). 젯슨이 퇴실 뒤 FIRST 결과를 보낸 다음 올려서 "마지막 퇴실 예약만" 규칙이 그대로 맞는다 */
    private static final Set<String> PHASES = Set.of("ENTRY", "FIRST", "RECHECK");

    private final CameraPhotoPort port;
    private final PhotoPort photos;
    private final PhotoPolicy policy;
    private final Clock clock;

    CameraPhotoService(CameraPhotoPort port, PhotoPort photos, PhotoPolicy policy, Clock clock) {
        this.port = port;
        this.photos = photos;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Uploaded upload(int roomId, long reservationId, String phase, String deviceId, byte[] data) {
        String p = phase(phase);
        if (p == null || deviceId == null || deviceId.isBlank() || deviceId.length() > 50
                || data == null || data.length == 0) {
            throw new BusinessException(ErrorCode.PHOTO_INVALID);
        }
        if (data.length > policy.maxBytes()) {
            throw new BusinessException(ErrorCode.PHOTO_TOO_LARGE);
        }
        String contentType = PhotoService.imageType(data);
        if (contentType == null) {
            throw new BusinessException(ErrorCode.PHOTO_INVALID);
        }
        if (!port.inRoom(roomId, reservationId)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        if (port.lastEnded(roomId).filter(id -> id == reservationId).isEmpty()) {
            throw new BusinessException(ErrorCode.TIDY_CHECK_NOT_LATEST);
        }
        Instant now = clock.instant();
        // 장치가 다시 보내도(와이파이 끊김) UNIQUE(reservation_id, phase)가 처음 사진만 남긴다
        if (!port.insertIfAbsent(reservationId, p, deviceId, contentType, data, now)) {
            throw new BusinessException(ErrorCode.PHOTO_ALREADY_UPLOADED);
        }
        return new Uploaded(reservationId, p, now.atZone(SEOUL), data.length);
    }

    @Override
    @Transactional(readOnly = true)
    public PhotoPort.StoredPhoto view(long reservationId, long userId, String phase) {
        String p = phase(phase);
        if (p == null || (!photos.isMember(reservationId, userId) && !photos.isAdmin(userId))) {
            throw new BusinessException(ErrorCode.PHOTO_NOT_FOUND);
        }
        return port.find(reservationId, p).orElseThrow(() -> new BusinessException(ErrorCode.PHOTO_NOT_FOUND));
    }

    private static String phase(String phase) {
        if (phase == null) {
            return null;
        }
        String p = phase.trim().toUpperCase(Locale.ROOT);
        return PHASES.contains(p) ? p : null;
    }
}
