package kr.ac.chosun.mspace.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeviceTokenVerifierTest {

    static DeviceTokenVerifier verifier(String shared, String devices) {
        return new DeviceTokenVerifier(new AccessPolicy(10, 5, 60, shared, devices));
    }

    static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        return null;
    }

    @Test
    @DisplayName("장치별 토큰은 묶인 방에서만 통과하고, 다른 방이면 403 DEVICE_ROOM_MISMATCH")
    void boundToRoom() {
        // given
        DeviceTokenVerifier v = verifier("", "keypad-1:1:aaa, qr-2:2:bbb");

        // when / then
        assertThatCode(() -> v.verify("Bearer aaa", 1, null)).doesNotThrowAnyException();
        assertThatCode(() -> v.verify("Bearer bbb", 2, null)).doesNotThrowAnyException();
        assertThat(codeOf(() -> v.verify("Bearer aaa", 2, null))).isEqualTo(ErrorCode.DEVICE_ROOM_MISMATCH);
        assertThat(codeOf(() -> v.verify("Bearer zzz", 1, null))).isEqualTo(ErrorCode.DEVICE_UNAUTHORIZED);
        assertThat(codeOf(() -> v.verify(null, 1, null))).isEqualTo(ErrorCode.DEVICE_UNAUTHORIZED);
        assertThat(codeOf(() -> v.verify("aaa", 1, null))).isEqualTo(ErrorCode.DEVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("옮기는 동안 예전 공용 토큰은 모든 방에 통과하고, 둘 다 비면 모두 401")
    void sharedTokenWhileMigrating() {
        // given
        DeviceTokenVerifier v = verifier("old", "keypad-1:1:aaa");
        DeviceTokenVerifier none = verifier("", "");

        // when / then
        assertThatCode(() -> v.verify("Bearer old", 1, null)).doesNotThrowAnyException();
        assertThatCode(() -> v.verify("Bearer old", 3, null)).doesNotThrowAnyException();
        assertThat(codeOf(() -> none.verify("Bearer ", 1, null))).isEqualTo(ErrorCode.DEVICE_UNAUTHORIZED);
        assertThat(codeOf(() -> none.verify("Bearer old", 1, null))).isEqualTo(ErrorCode.DEVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("장치별 토큰은 등록된 deviceId로만 보낼 수 있고, 다르면 403 DEVICE_ID_MISMATCH")
    void boundToDeviceId() {
        DeviceTokenVerifier v = verifier("old", "keypad-1:1:aaa");

        assertThatCode(() -> v.verify("Bearer aaa", 1, "keypad-1")).doesNotThrowAnyException();
        assertThat(codeOf(() -> v.verify("Bearer aaa", 1, "qr-1"))).isEqualTo(ErrorCode.DEVICE_ID_MISMATCH);
        assertThatCode(() -> v.verify("Bearer old", 1, "anything-1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("roomId가 없으면 토큰만 확인한다 (400은 부르는 쪽에서)")
    void noRoomOnlyToken() {
        DeviceTokenVerifier v = verifier("", "keypad-1:1:aaa");

        assertThatCode(() -> v.verify("Bearer aaa", null, null)).doesNotThrowAnyException();
        assertThat(codeOf(() -> v.verify("Bearer zzz", null, null))).isEqualTo(ErrorCode.DEVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("MSPACE_DEVICES 형식이 틀리거나 장치·토큰이 겹치면 서버를 띄우지 않는다")
    void badConfigFailsFast() {
        assertThatThrownBy(() -> verifier("", "keypad-1:1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier("", "keypad-1:x:aaa")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier("", "keypad-1:1:")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier("", "keypad-1:1:aaa,keypad-1:2:bbb")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier("", "keypad-1:1:aaa,qr-1:1:aaa")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier("", "keypad-1:1:aaa,")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier("", ",")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier("aaa", "keypad-1:1:aaa")).isInstanceOf(IllegalStateException.class);
    }
}
