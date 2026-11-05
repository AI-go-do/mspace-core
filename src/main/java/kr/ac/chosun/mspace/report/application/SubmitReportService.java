package kr.ac.chosun.mspace.report.application;

import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import kr.ac.chosun.mspace.room.application.RoomQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 (FR-RPT-101, DB 설계 V1 주석 8·8-1).
 * <ul>
 *   <li>소음은 "정말 소란인가요?" 체크가 없으면 400</li>
 *   <li>메모는 앞뒤 공백을 지우고, 비었으면 null. 최대 {@value #MAX_CONTENT_LENGTH}자</li>
 *   <li>없는 방이면 404 ROOM_NOT_FOUND (예약 만들기와 같은 코드. 권한 403과 헷갈리지 않게 먼저 본다)</li>
 *   <li>지금 그 방이나 옆 방을 쓰고 있는(IN_USE) 예약의 이용자만. 아니면 403</li>
 * </ul>
 * 사진(photo_url)은 저장소가 정해지면 사진 체크인(FR-RPT-102)과 같이 붙인다. 결과 알림과 관리자 판정(FR-RPT-103)은 다음 단계.
 */
@Service
public class SubmitReportService implements SubmitReportUseCase {

    static final int MAX_CONTENT_LENGTH = 500;

    private final ReportPort reports;
    private final RoomQuery rooms;

    public SubmitReportService(ReportPort reports, RoomQuery rooms) {
        this.reports = reports;
        this.rooms = rooms;
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
        String content = report.content() == null || report.content().isBlank() ? null : report.content().strip();
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "메모는 %d자 이내로 적어 주세요".formatted(MAX_CONTENT_LENGTH));
        }
        if (rooms.findActive(report.roomId()).isEmpty()) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        long basis = reports.findBasisReservation(userId, report.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_ALLOWED));
        ReportPort.Saved saved = reports.save(report.roomId(), userId, basis, report.category(), content);
        return new Submitted(saved.id(), report.roomId(), report.category(), ReportVerdict.OPEN, saved.createdAt());
    }
}
