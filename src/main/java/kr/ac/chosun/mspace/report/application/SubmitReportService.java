package kr.ac.chosun.mspace.report.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import kr.ac.chosun.mspace.common.config.ReportMessyPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 (FR-RPT-101, DB 설계 V1 주석 8·8-1).
 * <ul>
 *   <li>소음은 "정말 소란인가요?" 체크가 없으면 400</li>
 *   <li>메모는 앞뒤 공백을 지우고, 비었으면 null. 최대 {@value #MAX_CONTENT_LENGTH}자</li>
 *   <li>없는 방이면 404 ROOM_NOT_FOUND (예약 만들기와 같은 코드. 권한 403과 헷갈리지 않게 먼저 본다)</li>
 *   <li>지금 그 방이나 옆 방을 쓰고 있는(IN_USE) 예약의 이용자만. 아니면 403</li>
 *   <li>허위 신고 경고가 쌓여 신고 정지 중이면 403 REPORT_SUSPENDED (끝나는 시각 안내, FR-RPT-103 벌칙)</li>
 *   <li>어질러짐(MESSY)은 앞 팀 탓을 가리는 신고라 더 좁게 받는다 (11/8 지영):
 *     자기 방만(403 REPORT_MESSY_OWN_ROOM_ONLY), 연장으로 생긴 예약은 안 되고 입실 뒤
 *     mspace.report.messy.window-minutes(5분) 안에만(409 REPORT_MESSY_TOO_LATE),
 *     그 예약에 입실 사진이 있어야(409 REPORT_CHECKIN_PHOTO_REQUIRED)</li>
 * </ul>
 * 사진(photo_url)은 저장소가 정해지면 사진 체크인(FR-RPT-102)과 같이 붙인다. 결과 알림과 관리자 판정(FR-RPT-103)은 다음 단계.
 */
@Service
public class SubmitReportService implements SubmitReportUseCase {

    static final int MAX_CONTENT_LENGTH = 500;

    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final ReportPort reports;
    private final ReportPenaltyPort penalties;
    private final ReportMessyPolicy messy;
    private final Clock clock;

    public SubmitReportService(ReportPort reports, ReportPenaltyPort penalties, ReportMessyPolicy messy, Clock clock) {
        this.reports = reports;
        this.penalties = penalties;
        this.messy = messy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Submitted submit(long userId, NewReport report) {
        if (report.category() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "신고 종류(category)를 골라 주세요");
        }
        if (report.category() == ReportCategory.NOISE && !report.noiseConfirmed()) {
            throw new BusinessException(ErrorCode.REPORT_NOISE_NOT_CONFIRMED);
        }
        penalties.reportSuspendedUntil(userId, clock.instant()).ifPresent(until -> {
            throw new BusinessException(ErrorCode.REPORT_SUSPENDED, "허위 신고 경고가 쌓여 %s까지 신고할 수 없습니다"
                    .formatted(UNTIL.format(until.atZone(ZoneId.of("Asia/Seoul")))));
        });
        String content = report.content() == null || report.content().isBlank() ? null : report.content().strip();
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "메모는 %d자 이내로 적어 주세요".formatted(MAX_CONTENT_LENGTH));
        }
        // 사용 중지(is_active = false)된 방도 신고는 받는다: 지금 쓰는 사람이나 옆 방 이용자가 알릴 수 있어야 해서
        if (!reports.roomExists(report.roomId())) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        ReportPort.Basis basis = reports.findBasisReservation(userId, report.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_ALLOWED));
        if (report.category() == ReportCategory.MESSY) {
            checkMessy(basis, report.roomId());
        }
        // 내 방은 우리 팀만 쓴다. 화면이 내 방을 미리 골라 두면 소음이 내 방으로 잘못 들어가서 막는다 (11/13 지영)
        if (report.category() == ReportCategory.NOISE && basis.roomId() == report.roomId()) {
            throw new BusinessException(ErrorCode.REPORT_NOISE_NEIGHBOR_ONLY);
        }
        ReportPort.Saved saved = reports.save(report.roomId(), userId, basis.reservationId(), report.category(), content);
        return new Submitted(saved.id(), report.roomId(), report.category(), ReportVerdict.OPEN, saved.createdAt());
    }

    /** 어질러짐 신고 조건. 순서: 자기 방 → 연장 예약 → 5분 → 입실 사진 */
    private void checkMessy(ReportPort.Basis basis, int roomId) {
        if (basis.roomId() != roomId) {
            throw new BusinessException(ErrorCode.REPORT_MESSY_OWN_ROOM_ONLY);
        }
        if (basis.continued()) {
            // 연장하면 새 예약이 바로 IN_USE로 생겨 입실 시각이 새로 찍힌다. 같은 팀이 계속 쓴 방이라 받지 않는다
            throw new BusinessException(ErrorCode.REPORT_MESSY_TOO_LATE,
                    "연장한 예약은 어질러짐을 신고할 수 없습니다. 처음 입실한 뒤 %d분 안에 신고해 주세요"
                            .formatted(messy.windowMinutes()));
        }
        Instant deadline = basis.checkedInAt() == null ? null
                : basis.checkedInAt().plus(Duration.ofMinutes(messy.windowMinutes()));
        if (deadline == null || clock.instant().isAfter(deadline)) {
            throw new BusinessException(ErrorCode.REPORT_MESSY_TOO_LATE,
                    "어질러짐은 입실한 뒤 %d분 안에만 신고할 수 있습니다".formatted(messy.windowMinutes()));
        }
        if (!basis.hasCheckInPhoto()) {
            throw new BusinessException(ErrorCode.REPORT_CHECKIN_PHOTO_REQUIRED);
        }
    }
}
