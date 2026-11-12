package kr.ac.chosun.mspace.tidy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 카메라 경고가 꺼져 있을 때 다시 확인 알림 본문 (알림 본문 300자, V10) */
class StillLeftBodyTest {

    @Test
    @DisplayName("다시 확인 알림 본문은 물건이 많아도 300자 안이고, 경고가 없다는 문장은 늘 남는다")
    void fitsAndKeepsTail() {
        // given
        List<String> names = IntStream.range(0, 40).mapToObj(i -> "item-name-" + i).toList();

        // when
        String body = TidyCheckService.stillLeftBody("1번 방", names, "3시간");

        // then
        assertThat(body.length()).isLessThanOrEqualTo(300);
        assertThat(body).startsWith("퇴실 3시간 뒤에도 1번 방에 item-name-0").contains("외 ").endsWith("경고는 없어요.");
    }
}
