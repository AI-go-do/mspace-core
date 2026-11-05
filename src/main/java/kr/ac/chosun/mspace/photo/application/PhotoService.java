package kr.ac.chosun.mspace.photo.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.PhotoPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사진 체크인·아웃 (FR-RPT-102, 11/5 지영 "추천대로"). 선택 기능이라 사진이 없어도 입실·퇴실은 그대로다.
 * <ul>
 *   <li>그 예약의 대표자·참여자 누구나, 종류별 1장(먼저 올린 사진. 두 번째는 409)</li>
 *   <li>입실 사진은 사용 중(IN_USE)일 때, 퇴실 사진은 사용 중이거나 퇴실한 뒤 30분 안</li>
 *   <li>형식은 파일 앞부분(매직 넘버)으로 판단한다. 브라우저가 보낸 Content-Type은 믿지 않는다</li>
 *   <li>보는 사람은 그 예약 사람들과 관리자(신고 판정). 아니면 있는지도 알려 주지 않으려고 404</li>
 * </ul>
 * "지금"은 앱 Clock으로 잰다(체크인·정리와 같은 시계).
 */
@Service
class PhotoService implements PhotoUseCase {

    /** 퇴실로 끝난 상태. 이때만 퇴실 사진을 30분 더 받는다 (노쇼·취소는 방을 쓰지 않았으니 받지 않음) */
    private static final Set<String> CHECKED_OUT = Set.of("CHECKED_OUT", "AUTO_CHECKED_OUT", "AWAY_RELEASED");

    private final PhotoPort photos;
    private final PhotoPolicy policy;
    private final Clock clock;

    PhotoService(PhotoPort photos, PhotoPolicy policy, Clock clock) {
        this.photos = photos;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Uploaded upload(long reservationId, long userId, String kind, byte[] data) {
        PhotoKind photoKind = parseKind(kind).orElseThrow(() -> new BusinessException(ErrorCode.PHOTO_INVALID));
        if (data == null || data.length == 0) {
            throw new BusinessException(ErrorCode.PHOTO_INVALID);
        }
        if (data.length > policy.maxBytes()) {
            throw new BusinessException(ErrorCode.PHOTO_TOO_LARGE);
        }
        String contentType = imageType(data);
        if (contentType == null) {
            throw new BusinessException(ErrorCode.PHOTO_INVALID);
        }
        PhotoPort.ReservationView r = photos.findForMember(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        Instant now = clock.instant();
        if (!allowedNow(photoKind, r, now)) {
            throw new BusinessException(ErrorCode.PHOTO_NOT_ALLOWED_NOW);
        }
        // 같은 예약·종류를 둘이 동시에 올려도 UNIQUE(reservation_id, kind)가 하나만 남긴다
        if (!photos.insertIfAbsent(new PhotoPort.NewPhoto(reservationId, photoKind, userId, contentType, data, now))) {
            throw new BusinessException(ErrorCode.PHOTO_ALREADY_UPLOADED);
        }
        return new Uploaded(reservationId, photoKind, now.atZone(SEOUL), data.length);
    }

    @Override
    @Transactional(readOnly = true)
    public PhotoPort.StoredPhoto view(long reservationId, long userId, String kind) {
        PhotoKind photoKind = parseKind(kind).orElseThrow(() -> new BusinessException(ErrorCode.PHOTO_NOT_FOUND));
        if (!photos.isMember(reservationId, userId) && !photos.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.PHOTO_NOT_FOUND);
        }
        return photos.find(reservationId, photoKind)
                .orElseThrow(() -> new BusinessException(ErrorCode.PHOTO_NOT_FOUND));
    }

    @Override
    @Transactional
    public int deleteExpired() {
        return photos.deleteUploadedBefore(clock.instant().minus(Duration.ofDays(policy.retentionDays())));
    }

    private boolean allowedNow(PhotoKind kind, PhotoPort.ReservationView r, Instant now) {
        if ("IN_USE".equals(r.status())) {
            return true;
        }
        return kind == PhotoKind.CHECK_OUT && CHECKED_OUT.contains(r.status()) && r.checkedOutAt() != null
                && !now.isAfter(r.checkedOutAt().plus(Duration.ofMinutes(policy.checkoutGraceMinutes())));
    }

    private static java.util.Optional<PhotoKind> parseKind(String kind) {
        if (kind == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(PhotoKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    /** 파일 앞부분으로 형식 판단. JPEG는 FF D8 FF, PNG는 89 50 4E 47 0D 0A 1A 0A. 둘 다 아니면 null */
    static String imageType(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (d.length >= png.length && java.util.Arrays.equals(d, 0, png.length, png, 0, png.length)) {
            return "image/png";
        }
        return null;
    }
}
