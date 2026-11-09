package kr.ac.chosun.mspace.room.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** 관리자 기간 막기 사유 (Q-23). 학생 안내 문장에 label을 쓴다 */
public enum BlockReason {
    CONSTRUCTION("공사"),
    INSPECTION("점검"),
    RENTAL("대관"),
    OTHER("기타");

    private final String label;

    BlockReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("M/d HH:mm");

    /** 막힌 칸에 예약하려 할 때 학생 안내: "점검 중이라 11/10 12:00까지 예약할 수 없습니다" */
    public String blockedMessage(Instant until) {
        String what = this == OTHER ? "관리자가 막아 둔 시간" : label + " 중";
        return "%s이라 %s까지 예약할 수 없습니다".formatted(what, UNTIL.format(until.atZone(SEOUL)));
    }
}
