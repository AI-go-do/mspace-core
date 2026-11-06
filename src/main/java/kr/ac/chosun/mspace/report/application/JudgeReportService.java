package kr.ac.chosun.mspace.report.application;

import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 신고 판정 (FR-RPT-103). 사실(CONFIRMED) / 확인 불가(UNVERIFIED) / 허위(FALSE).
 * <ul>
 *   <li>관리자가 아니면 403 (예약 건수 API와 같은 ADMIN_ONLY)</li>
 *   <li>소음은 판정 대상이 아니라 409 (증명할 수 없어서. DB noise_not_false도 허위를 막는다)</li>
 *   <li>판정은 한 번만. 대기(OPEN)가 아니면 409. 두 관리자가 동시에 눌러도 조건부 UPDATE라 한 명만 된다</li>
 * </ul>
 * 경고·정지 기록은 벌칙이 확정되면 붙인다 (docs FR-RPT-103 "벌칙(제안)", Q-11).
 */
@Service
public class JudgeReportService implements JudgeReportUseCase {

    private final ReportJudgePort reports;

    public JudgeReportService(ReportJudgePort reports) {
        this.reports = reports;
    }

    @Override
    public List<ReportItem> list(long adminId, ReportVerdict verdict) {
        requireAdmin(adminId);
        return reports.list(verdict);
    }

    @Override
    @Transactional
    public ReportItem judge(long adminId, long reportId, ReportVerdict verdict) {
        requireAdmin(adminId);
        if (verdict == null || verdict == ReportVerdict.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판정(verdict)은 CONFIRMED, UNVERIFIED, FALSE 중 하나입니다");
        }
        return reports.judgeIfOpen(reportId, adminId, verdict).orElseThrow(() -> {
            ReportItem report = reports.find(reportId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
            if (report.category() == ReportCategory.NOISE) {
                return new BusinessException(ErrorCode.REPORT_NOT_JUDGEABLE);
            }
            return new BusinessException(ErrorCode.REPORT_ALREADY_JUDGED);
        });
    }

    private void requireAdmin(long userId) {
        if (!reports.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
    }
}
