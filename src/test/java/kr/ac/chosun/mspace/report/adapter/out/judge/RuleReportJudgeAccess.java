package kr.ac.chosun.mspace.report.adapter.out.judge;

import kr.ac.chosun.mspace.common.config.TidyPolicy;
import kr.ac.chosun.mspace.report.application.ReportJudge;

/** 테스트에서 규칙 판정기(패키지 안 클래스)를 감싸 쓰려고 */
public record RuleReportJudgeAccess(TidyPolicy tidy) {

    public ReportJudge judge() {
        return new RuleReportJudge(tidy);
    }
}
