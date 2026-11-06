package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.Optional;

/** 증거 사진 조회. 사진 내용(bytea)은 읽지 않고 있는지와 올린 시각만. 구현: JdbcReportEvidenceAdapter */
public interface ReportEvidencePort {

    /** @return 사진을 올린 시각. 없거나 30일이 지나 지워졌으면 빈 값 */
    Optional<Instant> photoUploadedAt(long reservationId, String kind);

    /** 예약의 입실·퇴실 시각 */
    Optional<Times> times(long reservationId);

    record Times(Instant checkedInAt, Instant checkedOutAt) {
    }
}
