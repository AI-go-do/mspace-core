package kr.ac.chosun.mspace.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 장치별 토큰은 그 방에만 쓸 수 있다 (요구사항 6-2장 "장치마다 따로", 11/6 지영·재웅). */
@SpringBootTest(properties = {"mspace.access.device-token=old-shared",
        "mspace.access.devices=keypad-1:1:room1-keypad,camera-2:2:room2-camera,sensor-1:1:room1-sensor"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeviceTokenApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    @DisplayName("1번 방 키패드 토큰으로 2번 방 입실 확인을 부르면 403 DEVICE_ROOM_MISMATCH")
    void keypadOtherRoom() {
        // given
        String body = "{\"eventId\":\"dt-1\",\"roomId\":2,\"deviceId\":\"keypad-1\",\"code\":\"123456\"}";

        // when / then
        assertThat(mvc.post().uri("/api/access/verify").header("Authorization", "Bearer room1-keypad")
                .contentType(MediaType.APPLICATION_JSON).content(body)).hasStatus(403)
                .bodyJson().extractingPath("$.code").isEqualTo("DEVICE_ROOM_MISMATCH");
    }

    @Test
    @DisplayName("방 카메라 토큰은 자기 방 current만 되고, 다른 방은 403")
    void cameraOwnRoomOnly() {
        assertThat(mvc.get().uri("/api/rooms/2/current").header("Authorization", "Bearer room2-camera"))
                .hasStatus(200);
        assertThat(mvc.get().uri("/api/rooms/1/current").header("Authorization", "Bearer room2-camera"))
                .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("DEVICE_ROOM_MISMATCH");
    }

    @Test
    @DisplayName("옮기는 동안 예전 공용 토큰은 어느 방이든 통과, 모르는 토큰은 401")
    void sharedWhileMigrating() {
        assertThat(mvc.get().uri("/api/rooms/1/current").header("Authorization", "Bearer old-shared"))
                .hasStatus(200);
        assertThat(mvc.get().uri("/api/rooms/2/current").header("Authorization", "Bearer old-shared"))
                .hasStatus(200);
        assertThat(mvc.get().uri("/api/rooms/1/current").header("Authorization", "Bearer nope"))
                .hasStatus(401);
    }

    @Test
    @DisplayName("1번 방 키패드 토큰으로 다른 장치 이름(qr-1)을 보내면 403 DEVICE_ID_MISMATCH")
    void keypadOtherDeviceId() {
        String body = "{\"eventId\":\"dt-2\",\"roomId\":1,\"deviceId\":\"qr-1\",\"code\":\"123456\"}";

        assertThat(mvc.post().uri("/api/access/verify").header("Authorization", "Bearer room1-keypad")
                .contentType(MediaType.APPLICATION_JSON).content(body)).hasStatus(403)
                .bodyJson().extractingPath("$.code").isEqualTo("DEVICE_ID_MISMATCH");
    }

    @Test
    @DisplayName("센서 이벤트와 정리 확인도 다른 방이면 403 DEVICE_ROOM_MISMATCH")
    void sensorAndTidyOtherRoom() {
        // given
        String sensor = "{\"eventId\":\"dt-s\",\"roomId\":2,\"deviceId\":\"sensor-1\",\"kind\":\"CO2\","
                + "\"ppm\":800,\"observedAt\":\"2026-11-06T10:00:00+09:00\"}";
        String tidy = "{\"eventId\":\"dt-t\",\"reservationId\":1,\"deviceId\":\"camera-2\",\"result\":\"CLEAN\","
                + "\"items\":[],\"leftItems\":[],\"observedAt\":\"2026-11-06T10:00:00+09:00\"}";

        // when / then
        assertThat(mvc.post().uri("/api/sensor-events").header("Authorization", "Bearer room1-sensor")
                .contentType(MediaType.APPLICATION_JSON).content(sensor)).hasStatus(403)
                .bodyJson().extractingPath("$.code").isEqualTo("DEVICE_ROOM_MISMATCH");
        assertThat(mvc.post().uri("/api/rooms/1/tidy-checks").header("Authorization", "Bearer room2-camera")
                .contentType(MediaType.APPLICATION_JSON).content(tidy)).hasStatus(403)
                .bodyJson().extractingPath("$.code").isEqualTo("DEVICE_ROOM_MISMATCH");
    }
}
