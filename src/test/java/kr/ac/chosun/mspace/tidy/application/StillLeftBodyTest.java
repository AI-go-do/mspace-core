package kr.ac.chosun.mspace.tidy.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 두고 간 물건 알림 본문: 물건 이름 없이 "물건"으로만 (11/12 지영) */
class StillLeftBodyTest {

    @Test
    @DisplayName("다시 확인 알림은 물건 이름 없이 남아 있다고만 하고, 경고가 없다는 문장을 남긴다")
    void stillLeftHasNoNames() {
        // when
        String body = TidyCheckService.stillLeftBody("1번 방", "3시간");

        // then
        assertThat(body).isEqualTo("퇴실 3시간 뒤에도 1번 방에 물건이 남아 있는 것 같아요. 카메라 확인이라 경고는 없어요.");
    }

    @Test
    @DisplayName("첫 확인 알림은 물건 이름 없이 두고 갔다고만 하고, 챙겨 갈 기한을 남긴다")
    void leftItemHasNoNames() {
        // when
        String body = TidyCheckService.leftItemBody("1번 방", "3시간");

        // then
        assertThat(body).isEqualTo("1번 방에 물건을 두고 가신 것 같아요. 3시간 안에 챙겨 가 주세요.");
    }
}
