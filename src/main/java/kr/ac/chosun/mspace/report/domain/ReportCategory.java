package kr.ac.chosun.mspace.report.domain;

/** 신고 종류 (FR-RPT-101). DB report.category CHECK와 같다 */
public enum ReportCategory {
    /** 비품 고장 */
    BROKEN,
    /** 창문 열림 */
    WINDOW_OPEN,
    /** 어질러짐 */
    MESSY,
    /** 소음. "정말 소란인가요?" 확인이 있어야 받는다. 증명할 수 없어서 허위 판정 대상이 아니다 */
    NOISE
}
