package kr.ac.chosun.mspace.rule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CheckinDeadlineGuardTest {

    private static final String TRIGGER = """
            IF NEW.is_instant THEN
                NEW.checkin_deadline := NEW.start_at + interval '10 minutes';
            ELSE
                NEW.checkin_deadline := greatest(NEW.start_at, NEW.created_at) + interval '15 minutes';
            END IF;
            """;

    @Test
    @DisplayName("설정(즉시 10·미리 15)이 트리거와 같으면 그대로 뜬다")
    void sameValuesPass() {
        assertThatCode(() -> CheckinDeadlineGuard.check(TRIGGER, 10, 15)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정만 20분으로 바꾸면 안내와 실제가 어긋나서 서버를 띄우지 않는다")
    void settingOnlyChangedFails() {
        assertThatThrownBy(() -> CheckinDeadlineGuard.check(TRIGGER, 10, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation_set_deadline");
    }
}
