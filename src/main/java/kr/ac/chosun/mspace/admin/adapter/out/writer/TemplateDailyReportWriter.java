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
    static final String VERSION = "daily-template-v2";

    @Override
    public Written write(LocalDate date, Facts f) {
        List<String> lines = new ArrayList<>();
        String day = "%d월 %d일%s".formatted(date.getMonthValue(), date.getDayOfMonth(), f.partial() ? "(지금까지)" : "");
        var r = f.total().reservations();
        lines.add("%s 예약 %d건 중 %d건 이용, 노쇼 %d건, 취소 %d건이에요.".formatted(day, r.total(), r.used(), r.noShow(),
                r.canceled()));
        f.rooms().stream().filter(u -> u.minutes().used() > 0).max(Comparator.comparingLong(u -> u.minutes().used()))
                .ifPresentOrElse(u -> lines.add("가장 오래 쓴 방은 %s(%d분)이에요.".formatted(u.roomName(), u.minutes().used())),
                        () -> lines.add("방을 쓴 기록은 없어요."));
        long reports = f.reports().values().stream().flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum();
        long confirmed = verdict(f.reports(), "CONFIRMED");
        long open = verdict(f.reports(), "OPEN");
        long warnings = f.warnings().values().stream().mapToLong(Long::longValue).sum();
        lines.add("신고 %d건(사실 %d, 대기 %d), 경고 %d건이에요.".formatted(reports, confirmed, open, warnings));
        long appeals = f.appeals().values().stream().mapToLong(Long::longValue).sum();
        long faults = f.deviceFaults().values().stream().mapToLong(Long::longValue).sum();
        if (appeals > 0 || faults > 0) {
            // appeals는 그날 정해진 결과 + 그날 들어와 대기 중인 것이라, 처리와 대기를 나눠 쓴다
            long withdrawn = f.appeals().getOrDefault("WITHDRAWN", 0L);
            long kept = f.appeals().getOrDefault("KEPT", 0L);
            lines.add("경고 철회 요청 처리 %d건(철회 %d, 유지 %d), 새로 들어와 대기 %d건, 장치 고장 %d건이에요.".formatted(
                    withdrawn + kept, withdrawn, kept, f.appeals().getOrDefault("PENDING", 0L), faults));
        }
        if (!f.aiActions().isEmpty()) {
            lines.add("AI가 미리 환기 %d번, 더움 안내 %d번, 시간 넘김 안내 %d번 했고 센서 고장 %d건, 예약 없는 사용 %d건을 남겼어요."
                    .formatted(f.aiActions().getOrDefault("VENT_SOON", 0L), f.aiActions().getOrDefault("ROOM_HOT", 0L),
                            f.aiActions().getOrDefault("OVERTIME", 0L), f.aiActions().getOrDefault("SENSOR_DOWN", 0L),
                            f.aiActions().getOrDefault("UNBOOKED", 0L)));
        }
        Usage t = f.total();
        if (t.sensor().co2Readings() > 0) {
            lines.add("CO2는 평균 %dppm, 가장 높을 때 %dppm, 환기 알림 %d번이에요.".formatted(t.sensor().co2AvgPpm(),
                    t.sensor().co2MaxPpm(), t.sensor().ventilationAlerts()));
        }
        // 이용 한 줄 + 방 한 줄 + 신고·경고 한 줄은 늘 있어 3줄 아래로 내려가지 않는다 (V34 CHECK 3–5)
        return new Written(List.copyOf(lines.subList(0, Math.min(5, lines.size()))), NAME, VERSION);
    }

    private static long verdict(Map<String, Map<String, Long>> reports, String verdict) {
        return reports.values().stream().mapToLong(m -> m.getOrDefault(verdict, 0L)).sum();
    }
}
