package kr.ac.chosun.mspace.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaskedNameTest {

    @Test
    @DisplayName("세 글자 이상은 첫·끝 글자만 남긴다: 김지영 → 김*영, 남궁민수 → 남**수")
    void longNames() {
        assertThat(MaskedName.of("김지영")).isEqualTo("김*영");
        assertThat(MaskedName.of("남궁민수")).isEqualTo("남**수");
    }

    @Test
    @DisplayName("두 글자는 끝 글자를 가리고, 한 글자는 전부 가리고, 빈 값은 빈 문자열")
    void shortNames() {
        assertThat(MaskedName.of("정하")).isEqualTo("정*");
        assertThat(MaskedName.of("솔")).isEqualTo("*");
        assertThat(MaskedName.of(" ")).isEmpty();
        assertThat(MaskedName.of(null)).isEmpty();
    }
}
