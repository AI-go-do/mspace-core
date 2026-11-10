package kr.ac.chosun.mspace.appeal.adapter.out.reviewer;

import java.util.List;
import java.util.Objects;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer;
import kr.ac.chosun.mspace.common.config.TidyPolicy;

/**
 * 외부 AI 없이 정해진 규칙으로 보는 판단기. AI 어댑터(Gemini 등)가 생기기 전까지 쓰고, 생긴 뒤에는 비교 기준이 된다.
 * <ul>
 *   <li>카메라 경고(두고 간 물건): 다시 확인 때 남은 물건의 가장 높은 확신도가 경고 기준(mspace.tidy.warn-min-confidence)
 *       이상이면 유지, 그 확신도를 그대로 낸다. 기준 아래면 철회</li>
 *   <li>학생 신고로 받은 경고: 이 판단기는 신고 글·사진을 읽지 못해 확신도 0으로 철회 (Q-24 ③ 확인 불가 = 철회)</li>
 * </ul>
 * 학생 사유는 읽지 않는다 (규칙으로 판단할 수 있는 근거만 본다).
 * Gemini 키가 없거나 Gemini가 답하지 못하면 이 판단기가 대신 본다 ({@link ReviewerConfig}).
 */
class RuleWarningReviewer implements WarningReviewer {

    static final String NAME = "rules";
    static final String VERSION = "rules-v1";

    private final TidyPolicy tidy;

    RuleWarningReviewer(TidyPolicy tidy) {
        this.tidy = tidy;
    }

    @Override
    public Review review(Case c) {
        double best = c.recheckConfidences().stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(-1);
        if ("CAMERA".equals(c.reportSource()) && best >= 0) {
            String seen = "퇴실 %s 뒤 방 카메라가 다시 봤을 때 물건이 남아 있었어요(가장 높은 확신도 %.2f)."
                    .formatted(tidy.recheckAfterText(), best);
            if (best >= tidy.warnMinConfidence()) {
                return new Review(Decision.KEPT, best,
                        List.of(seen, "찾으러 올 수 있는 시간 안에 가져가지 않아 경고를 유지해요."),
                        List.of("TIDY_CHECK"), NAME, VERSION);
            }
            return new Review(Decision.WITHDRAWN, 1 - best,
                    List.of(seen, "확신도가 경고 기준(%.2f)보다 낮아 경고를 철회해요.".formatted(tidy.warnMinConfidence())),
                    List.of("TIDY_CHECK"), NAME, VERSION);
        }
        return new Review(Decision.WITHDRAWN, 0,
                List.of("이 경고는 신고 내용과 사진을 다시 봐야 하는데, 지금은 자동으로 확인할 수 없어요.",
                        "확인할 수 없으면 벌칙을 주지 않는 원칙에 따라 경고를 철회해요."),
                List.of(), NAME, VERSION);
    }
}
