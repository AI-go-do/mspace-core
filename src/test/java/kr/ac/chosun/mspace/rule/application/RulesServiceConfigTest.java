package kr.ac.chosun.mspace.rule.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.config.ExtensionPolicy;
import kr.ac.chosun.mspace.common.config.NoShowPenaltyPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.config.RulesProperties;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 설정값을 기본값과 다르게 바꾸면 GET /api/rules 문구·숫자도 같이 바뀌는지 (DB 없이 서비스만) */
class RulesServiceConfigTest {

    @Test
    @DisplayName("노쇼 벌칙·입실 기한·두고 간 물건 기한 설정을 바꾸면 이용 수칙 문구의 숫자도 바뀐다")
    void conductFollowsChangedSettings() {
        // given: 기본값(15분·10분, 30일 안 2번이면 14일)과 다른 값
        RulesProperties props = new RulesProperties(20, 5, new RulesProperties.Reminders("입실", "퇴실"), List.of(
                new RulesProperties.Conduct("NO_SHOW", "노쇼",
                        "시작 {noShowMinutes}분 안에 키패드로 입실해요(지금 바로 쓰기는 {instantNoShowMinutes}분)",
                        "{penaltyWindowDays}일 안에 {penaltyCount}번이면 {suspendDays}일 동안 예약·참여 모두 못 해요"),
                new RulesProperties.Conduct("BELONGINGS", "소지품", "퇴실 전에 챙겨요",
                        "{leftItemsWithin} 뒤에도 남아 있으면 대표자에게 경고 1번")));

        // when
        RulesService.Rules rules = new RulesService(new ReservationPolicy(7, 30, 120), new ExtensionPolicy(1, 10, 120, 30),
                new AccessPolicy(10, 5, 60, "token", ""), new NoShowPenaltyPolicy(3, 60, 7), props,
                new TidyPolicy(150, 0.8, 10, true)).rules();

        // then
        RulesProperties.Conduct noShow = rules.conduct().get(0);
        assertThat(noShow.rule()).isEqualTo("시작 20분 안에 키패드로 입실해요(지금 바로 쓰기는 5분)");
        assertThat(noShow.penalty()).isEqualTo("60일 안에 3번이면 7일 동안 예약·참여 모두 못 해요");
        assertThat(rules.reservation().noShowMinutes()).isEqualTo(20);
        assertThat(rules.reservation().instantNoShowMinutes()).isEqualTo(5);
        // 시간으로 나누어떨어지지 않는 기한도 시간·분으로 (알림 문구와 같은 TidyPolicy.recheckAfterText)
        assertThat(rules.conduct().get(1).penalty()).isEqualTo("2시간 30분 뒤에도 남아 있으면 대표자에게 경고 1번");
    }
}
