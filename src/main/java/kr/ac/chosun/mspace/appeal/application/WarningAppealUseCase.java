package kr.ac.chosun.mspace.appeal.application;

import java.time.Instant;
import java.util.List;

/** 내 경고 목록과 철회 요청 (FR-RPT-105). 로그인한 본인 경고만 */
public interface WarningAppealUseCase {

    /** 받은 경고 한 줄. 철회 요청을 보냈으면 appeal이 채워진다 */
    record WarningItem(long id, String reason, String reportType, int roomId, Long reservationId,
                       Instant startAt, Instant endAt, Instant createdAt, String why, Instant appealableUntil,
                       Appeal appeal) {
    }

    /** @param status PENDING(판단 중) · WITHDRAWN(철회) · KEPT(유지) */
    record Appeal(String status, String text, Instant createdAt, Instant decidedAt, String answer) {
    }

    /** 최근 days일 안에 받은 내 경고. 최근 것부터 */
    List<WarningItem> mine(long userId, int days);

    /**
     * 철회 요청. 받은 뒤 7일 안, 경고마다 1번.
     * 남의 경고 404 WARNING_NOT_FOUND, 기간 지남 409 WARNING_APPEAL_EXPIRED, 이미 보냄 409 WARNING_APPEAL_DUPLICATE,
     * 사유 10–500자가 아니거나 긴 숫자(학번·전화)가 있으면 400 INVALID_REQUEST
     *
     * @param evidence 같이 볼 기록: CHECKIN_PHOTO · CHECKOUT_PHOTO · TIDY_CHECK
     */
    WarningItem appeal(long userId, long warningId, String text, List<String> evidence);
}
