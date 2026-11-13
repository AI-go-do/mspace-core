package kr.ac.chosun.mspace.admin.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import kr.ac.chosun.mspace.admin.application.DailyReportPort.Saved;
import kr.ac.chosun.mspace.admin.application.DailyReportWriter.Written;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.UsageStats;
import kr.ac.chosun.mspace.common.config.DailyReportPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 관리자 하루 운영 보고 (11/12 지영 "core 다 AI화", 11/10 회의 "관리자 = 관제 대시보드").
 * <ul>
 *   <li>숫자는 매번 새로 센다(이용 통계와 같은 쿼리 + 신고·경고·철회·고장 GROUP BY). 글(요약)만 날짜별로 보관해 다시 쓴다</li>
 *   <li>글은 그때 본 숫자의 지문과 같이 보관하고, 숫자가 바뀌면 새로 쓴다(오늘은 refresh 1시간 안에선 기다림). 자세한 건 reusable</li>
 *   <li>요약 쓰기(외부 AI)는 트랜잭션 밖. 보관은 한 줄 upsert라 관리자 둘이 같이 열어도 문제없다</li>
 * </ul>
 */
@Service
class DailyReportService implements DailyReportUseCase {

    private final UsageStatsUseCase stats;
    private final DailyReportPort port;
    private final DailyReportWriter writer;
    private final DailyReportPolicy policy;
    private final Clock clock;

    DailyReportService(UsageStatsUseCase stats, DailyReportPort port, DailyReportWriter writer, DailyReportPolicy policy,
                       Clock clock) {
        this.stats = stats;
        this.port = port;
        this.writer = writer;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public DailyReport report(long adminId, LocalDate date) {
        // 날짜와 시각을 한 번 읽은 시계 값으로 정한다 (자정 근처에 둘이 다른 날을 가리키지 않게)
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, SEOUL);
        LocalDate day = date == null ? today : date;
        if (day.isAfter(today)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "아직 오지 않은 날의 보고는 볼 수 없습니다");
        }
        // 관리자 확인과 날짜 범위(2000–2100년) 확인은 이용 통계가 한다
        UsageStats usage = stats.usage(adminId, day, day);
        Instant from = day.atStartOfDay(SEOUL).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(SEOUL).toInstant();
        Facts facts = new Facts(day.equals(today), usage.total(), usage.rooms(), port.reports(from, to),
                port.autoJudged(from, to), port.warnings(from, to), port.appeals(from, to), port.deviceFaults(from, to),
                port.aiActions(from, to));

        String hash = Integer.toHexString(facts.hashCode());

        Optional<Saved> saved = port.saved(day);
        if (saved.isPresent() && reusable(saved.get(), hash, day.equals(today), now)) {
            return new DailyReport(day, saved.get().lines(), saved.get().writer(), saved.get().generatedAt(), facts);
        }
        Written w = writer.write(day, facts);
        port.save(day, w.lines(), w.writer(), w.promptVersion(), hash, now);
        return new DailyReport(day, w.lines(), w.writer(), now, facts);
    }

    /**
     * 보관한 글을 그대로 써도 되나.
     * <ul>
     *   <li>Gemini 실패로 대신 쓴 글: refresh 안이고 숫자도 같을 때만 (지난 날도 refresh가 지나면 Gemini로 다시 써 본다)</li>
     *   <li>글을 쓸 때 본 숫자와 지금 숫자가 같으면: 그대로 (외부 AI를 다시 부르지 않음)</li>
     *   <li>숫자가 바뀌었으면: 오늘은 refresh(1시간) 안에서만 그대로 두고(무료 한도), 지난 날은 바로 새로 쓴다</li>
     * </ul>
     * 오늘 글이 최대 refresh만큼 숫자보다 늦을 수 있어 generatedAt을 같이 돌려준다(화면이 "몇 시 기준"으로 보여 줌).
     */
    private boolean reusable(Saved saved, String hash, boolean today, Instant now) {
        boolean withinRefresh = !saved.generatedAt().plus(policy.refresh()).isBefore(now);
        if (DailyReportWriter.FALLBACK.equals(saved.writer())) {
            return withinRefresh && saved.factsHash().equals(hash);
        }
        if (saved.factsHash().equals(hash)) {
            return true;
        }
        return today && withinRefresh;
    }
}
