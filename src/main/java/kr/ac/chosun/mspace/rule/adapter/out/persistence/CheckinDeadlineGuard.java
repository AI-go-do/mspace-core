package kr.ac.chosun.mspace.rule.adapter.out.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.common.config.RulesProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 안내용 노쇼 분(mspace.rules)이 DB 트리거 reservation_set_deadline이 실제로 거는 입실 기한과 같은지 본다.
 * 트리거는 V1에 10분(즉시 시작)·15분(미리 예약)이 SQL로 박혀 있어서, 설정만 바꾸면 안내와 실제가 어긋난다.
 * 다르면 서버를 띄우지 않는다 (바꾸려면 새 Flyway 파일로 트리거도 같이).
 */
@Component
class CheckinDeadlineGuard implements ApplicationRunner {

    private static final Pattern MINUTES = Pattern.compile("interval '(\\d+) minutes'");

    private final JdbcClient jdbc;
    private final RulesProperties props;

    CheckinDeadlineGuard(JdbcClient jdbc, RulesProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 함수 이름이 아니라 reservation 표에 실제로 걸린(켜진) 트리거의 함수 본문을 읽는다
        // 보통 연결(session_replication_role = origin)에서 도는 트리거만: 'O'(기본)·'A'(항상). 'R'(replica 전용)·'D'(꺼짐)는 안 돈다
        String source = jdbc.sql("""
                        SELECT p.prosrc
                        FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                        WHERE t.tgrelid = 'reservation'::regclass AND t.tgname = 'reservation_set_deadline'
                          AND t.tgenabled IN ('O', 'A')
                        """)
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("reservation 표에 켜진 reservation_set_deadline 트리거가 없습니다"));
        check(source, props.instantNoShowMinutes(), props.noShowMinutes());
    }

    /** 트리거 본문은 IF is_instant(즉시 시작) → ELSE(미리 예약) 순서라 첫 번째·두 번째 interval을 비교한다 */
    static void check(String triggerSource, int instantMinutes, int advanceMinutes) {
        List<Integer> found = new ArrayList<>();
        Matcher m = MINUTES.matcher(triggerSource);
        while (m.find()) {
            found.add(Integer.parseInt(m.group(1)));
        }
        if (!found.equals(List.of(instantMinutes, advanceMinutes))) {
            throw new IllegalStateException(
                    "mspace.rules 노쇼 분(즉시 %d, 미리 %d)이 DB 트리거 reservation_set_deadline %s와 다릅니다. 설정을 되돌리거나 새 Flyway 파일로 트리거를 같이 바꾸세요"
                            .formatted(instantMinutes, advanceMinutes, found));
        }
    }
}
