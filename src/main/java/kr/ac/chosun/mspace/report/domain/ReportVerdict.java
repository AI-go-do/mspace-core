package kr.ac.chosun.mspace.report.domain;

/** 관리자 판정 (FR-RPT-103). 신고하면 OPEN(대기)으로 시작한다. DB report.verdict CHECK와 같다 */
public enum ReportVerdict {
    OPEN, CONFIRMED, UNVERIFIED, FALSE
}
