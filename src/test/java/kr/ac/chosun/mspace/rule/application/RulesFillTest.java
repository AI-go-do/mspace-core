package kr.ac.chosun.mspace.rule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 이용 수칙 문구의 {이름} 자리 채우기. 오타가 있으면 응답에 그대로 나가지 않게 시작할 때 멈춘다 */
class RulesFillTest {

    final Map<String, String> numbers = Map.of("noShowMinutes", "15", "suspendDays", "14");

    @Test
    @DisplayName("아는 이름은 모두 숫자로 채운다")
    void fillsKnownNames() {
        assertThat(RulesService.fill("{noShowMinutes}분 안에 입실, 아니면 {suspendDays}일 정지", numbers))
                .isEqualTo("15분 안에 입실, 아니면 14일 정지");
    }

    @Test
    @DisplayName("모르는 이름, 숫자·밑줄이 든 이름, 닫히지 않은 자리는 모두 오류")
    void rejectsLeftovers() {
        for (String text : new String[] {"{noShow}분", "{suspend_days}일", "{suspendDays1}일", "{noShowMinutes분", "분}"}) {
            assertThatThrownBy(() -> RulesService.fill(text, numbers)).isInstanceOf(IllegalStateException.class);
        }
    }
}
