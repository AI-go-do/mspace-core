package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.List;

/**
 * 신고 판정용 증거 사진 (11/6 지영 요청). 관리자가 사실인지 바로 보게, 직전 이용 예약의 퇴실 사진과
 * 신고자 예약의 입실 사진이 있는지와 사진 주소를 준다. 구현: {@link ReportEvidenceService}
 */
public interface ReportEvidenceUseCase {

    /** 관리자만. 없는 신고 404 */
    Evidence evidence(long adminId, long reportId);

    /**
     * @param previous 직전 이용 예약 (경고 대상과 같은 규칙: 같은 방, 신고자 예약 제외, 신고 전에 입실한 것 중 가장 늦은 것).
     *                 없으면 null
     * @param reporter 신고자 예약의 입실 사진. 방 카메라 자동 신고(source CAMERA)는 신고자가 없어 null
     * @param note     사진이 없을 때 이유 한 줄 (둘 다 있으면 null)
     */
    record Evidence(long reportId, Previous previous, Side reporter, String note) {
    }

    /** @param tidyCheck 그 예약이 끝난 뒤 방 카메라가 낸 정리 확인 결과. 카메라가 안 봤으면 null */
    record Previous(long reservationId, Instant checkedInAt, Instant checkedOutAt, Photo photo, Tidy tidyCheck) {
    }

    /**
     * 방 카메라 정리 확인 결과 (FR-DEV-105). 사진은 없고 글자만.
     * @param result    CLEAN 또는 NEEDS_CLEANING
     * @param leftItems 두고 간 귀중품 (BAG·PHONE·EARBUDS·LAPTOP)
     */
    record Tidy(String result, List<String> items, List<String> leftItems, Instant observedAt) {
    }

    record Side(long reservationId, Photo photo) {
    }

    /**
     * @param exists 사진이 있으면 true. false면 url·uploadedAt은 null
     * @param url    관리자가 그대로 열 수 있는 사진 주소 (GET /api/reservations/{id}/photos/{kind})
     */
    record Photo(String kind, boolean exists, String url, Instant uploadedAt) {
    }
}
