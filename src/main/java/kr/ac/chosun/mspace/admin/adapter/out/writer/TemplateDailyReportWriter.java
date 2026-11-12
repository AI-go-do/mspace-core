package kr.ac.chosun.mspace.admin.adapter.out.writer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.admin.application.DailyReportUseCase.Facts;
import kr.ac.chosun.mspace.admin.application.DailyReportWriter;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Usage;

/**
 * 외부 AI 없이 정해진 틀로 쓰는 하루 운영 보고. Gemini 키가 없거나 Gemini가 실패하면 이것을 쓴다.
 * 숫자를 그대로 옮기기만 해서 틀릴 일이 없고, 무엇이 눈에 띄는지는 고르지 않는다.
 */
class TemplateDailyReportWriter implements DailyReportWriter {

    static final String NAME = "template";
    static final String VERSION = "daily-template-v1";

    @Override
    public Written write(LocalDate date, Facts f) {
        List<String> lines = new ArrayList<>();
        String day = "%d월 %d일%s".formatted(date.getMonthValue(), date.getDayOfMonth(), f.partial() ? "(지금까지)" : "");
        var r = f.total().reservations();
        lines.add("%s 예약 %d건 중 %d건 이용, 노쇼 %d건, 취소 %d건이에요.".formatted(day, r.total(), r.used(), r.noShow(),
                r.canceled()));
        f.rooms().stream().filter(u -> u.minutes().used() > 0).max(Comparator.comparingLong(u -> u.minutes().used()))
                .ifPresent(u -> lines.add("가장 오래 쓴 방은 %s(%d분)이에요.".formatted(u.roomName(), u.minutes().used())));
        long reports = f.reports().values().stream().flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum();
        long confirmed = verdict(f.reports(), "CONFIRMED");
        long open = verdict(f.reports(), "OPEN");
        long warnings = f.warnings().values().stream().mapToLong(Long::longValue).sum();
        lines.add("신고 %d건(사실 %d, 대기 %d), 경고 %d건이에요.".formatted(reports, confirmed, open, warnings));
        long appeals = f.appeals().values().stream().mapToLong(Long::longValue).sum();
        long faults = f.deviceFaults().values().stream().mapToLong(Long::longValue).sum();
        if (appeals > 0 || faults > 0) {
            lines.add("경고 철회 요청 %d건(철회 %d, 유지 %d), 장치 고장 %d건이에요.".formatted(appeals,
                    f.appeals().getOrDefault("WITHDRAWN", 0L), f.appeals().getOrDefault("KEPT", 0L), faults));
        }
        Usage t = f.total();
        if (t.sensor().co2Readings() > 0) {
            lines.add("CO2는 평균 %dppm, 가장 높을 때 %dppm, 환기 알림 %d번이에요.".formatted(t.sensor().co2AvgPpm(),
                    t.sensor().co2MaxPpm(), t.sensor().ventilationAlerts()));
        }
        return new Written(List.copyOf(lines.subList(0, Math.min(5, lines.size()))), NAME, VERSION);
    }

    private static long verdict(Map<String, Map<String, Long>> reports, String verdict) {
        return reports.values().stream().mapToLong(m -> m.getOrDefault(verdict, 0L)).sum();
    }
}
