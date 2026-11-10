package kr.ac.chosun.mspace.photo.application;

import java.time.Instant;
import java.util.Optional;

/**
 * 사진 저장과 권한 판단에 필요한 조회. 저장 위치(지금은 DB)가 바뀌면 이 포트의 어댑터만 바꾼다.
 */
public interface PhotoPort {

    /** 내가(대표자·참여자) 들어간 예약의 상태와 퇴실 시각. 내 예약이 아니면 빈 값 */
    Optional<ReservationView> findForMember(long reservationId, long userId);

    boolean isMember(long reservationId, long userId);

    boolean isAdmin(long userId);

    /**
     * 사진 1장을 저장한다. 같은 예약·종류가 이미 있으면 저장하지 않는다(먼저 올린 사진).
     *
     * @return 저장했으면 true, 이미 있었으면 false
     */
    boolean insertIfAbsent(NewPhoto photo);

    Optional<StoredPhoto> find(long reservationId, PhotoKind kind);

    /** 이 시각보다 먼저 올린 사진을 지운다 (학생 사진 + 방 카메라 사진) */
    int deleteUploadedBefore(Instant cutoff);

    /** @param checkedOutAt 퇴실·자동 퇴실·자리 비움 퇴실 시각. 아직이면 null */
    record ReservationView(String status, Instant checkedOutAt) {
    }

    record NewPhoto(long reservationId, PhotoKind kind, long uploadedBy, String contentType, byte[] data,
                    Instant uploadedAt) {
    }

    record StoredPhoto(String contentType, byte[] data) {
    }
}
