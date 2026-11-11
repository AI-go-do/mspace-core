package kr.ac.chosun.mspace.report.adapter.out.judge;

import java.util.List;
import java.util.Objects;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import kr.ac.chosun.mspace.report.application.ReportJudge;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.springframework.stereotype.Component;

/**
 * 외부 AI 없이 정해진 규칙으로 보는 신고 판정기 (FR-RPT-103 10차, Q-28 정량 기준 추천안).
 * <ul>
 *   <li>어질러짐: 앞 팀 방 카메라(다시 확인 RECHECK가 있으면 그것, 없으면 퇴실 직후 FIRST)가 물건을 경고 기준(mspace.tidy.warn-min-confidence, 0.8) 이상으로
 *       봤으면 사실. 그 확신도를 그대로 낸다. 아니면 확인 불가</li>
 *   <li>앞 팀이 같은 퇴실로 이미 카메라 경고를 받았거나, 찾으러 갈게요 QR로 문을 열었으면 확인 불가 (두 번 벌하지 않음)</li>
 *   <li>비품 고장·창문 열림: 자동으로 볼 근거(센서·사진 판독)가 없어 확인 불가</li>
 *   <li>카메라 경고가 꺼져 있으면(mspace.tidy.camera-warn, 11/11 지영: 정확도 51%) 카메라만으로는 사실로 정하지 않고 확인 불가</li>
 *   <li>허위는 내지 않는다: 카메라가 깨끗하다고 봐도 못 본 것일 수 있어, 그것만으로 신고자를 벌하지 않는다</li>
 * </ul>
 * 신고 메모는 읽지 않는다 (규칙으로 판단할 수 있는 근거만 본다).
 */
@Component
class RuleReportJudge implements ReportJudge {

    static final String NAME = "rules";
    static final String VERSION = "report-rules-v1";

    private final TidyPolicy tidy;

    RuleReportJudge(TidyPolicy tidy) {
        this.tidy = tidy;
    }

    @Override
    public Judgement judge(Case c) {
        if ("MESSY".equals(c.category()) && (c.previousWarned() || c.previousRetrieved())) {
            // 이미 카메라 경고를 받았거나 찾으러 왔으면 같은 물건으로 다시 벌하지 않는다 (11/10 "두 번 걸리지 않게")
            return new Judgement(ReportVerdict.UNVERIFIED, 1,
                    List.of(c.previousWarned()
                            ? "앞 팀은 같은 물건으로 이미 경고를 받았어요. 한 번 더 경고하지 않아요."
                            : "앞 팀이 두고 간 물건을 찾으러 왔어요. 카메라 결과만으로 경고하지 않아요."),
                    List.of("TIDY_CHECK"), NAME, VERSION);
        }
        if ("MESSY".equals(c.category()) && c.previousTidyResult() != null) {
            double best = c.previousConfidences().stream().filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue).max().orElse(-1);
            if (tidy.cameraWarn() && "LEFT_ITEMS".equals(c.previousTidyResult()) && best >= tidy.warnMinConfidence()) {
                return new Judgement(ReportVerdict.CONFIRMED, best,
                        List.of("앞 팀이 퇴실한 뒤 방 카메라가 남은 물건을 봤어요(가장 높은 확신도 %.2f).".formatted(best)),
                        List.of("TIDY_CHECK"), NAME, VERSION);
            }
            return new Judgement(ReportVerdict.UNVERIFIED, 1,
                    List.of("앞 팀이 퇴실한 뒤 방 카메라 결과로는 어질러졌는지 확인할 수 없었어요."),
                    List.of("TIDY_CHECK"), NAME, VERSION);
        }
        return new Judgement(ReportVerdict.UNVERIFIED, 1,
                List.of("이 신고는 자동으로 확인할 근거가 없어요. 확인할 수 없으면 벌칙을 주지 않아요."),
                List.of(), NAME, VERSION);
    }
}
