package kr.ac.chosun.mspace.admin.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 하루 운영 보고 숫자 세기와 요약 보관 */
public interface DailyReportPort {

    /** 신고 종류 → 판정 → 건수 (신고가 [from, to)에 들어온 것) */
    Map<String, Map<String, Long>> reports(Instant from, Instant to);

    /** [from, to)에 들어온 신고 중 판정기가 정한 건수 */
    long autoJudged(Instant from, Instant to);

    Map<String, Long> warnings(Instant from, Instant to);

    /** 그 기간에 정해진 결과(WITHDRAWN·KEPT) + 그 기간에 들어와 아직 대기(PENDING) */
    Map<String, Long> appeals(Instant from, Instant to);

    Map<String, Long> deviceFaults(Instant from, Instant to);

    Optional<Saved> saved(LocalDate date);

    void save(LocalDate date, List<String> lines, String writer, String promptVersion, Instant generatedAt);

    record Saved(List<String> lines, String writer, Instant generatedAt) {
    }
}
