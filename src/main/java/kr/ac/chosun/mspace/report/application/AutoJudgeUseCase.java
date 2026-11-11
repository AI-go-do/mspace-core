package kr.ac.chosun.mspace.report.application;

/** 판정 대기 신고를 판정기로 정한다 (FR-RPT-103 10차). 스케줄러가 1분마다 부른다 */
public interface AutoJudgeUseCase {

    /** @return 이번에 판정한 신고 수 */
    int judgeOpen();
}
