package kr.ac.chosun.mspace.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 장치(키패드·QR·센서·방 카메라)가 보내는 Authorization: Bearer 장치 토큰 확인.
 * 장치별 토큰(mspace.access.devices, "deviceId:roomId:토큰")은 그 방에만 쓸 수 있다. 다른 방이면 403 DEVICE_ROOM_MISMATCH.
 * 옮기는 동안은 예전 공용 토큰(mspace.access.device-token)도 모든 방에 받는다(11/6 지영·재웅).
 */
@Component
public class DeviceTokenVerifier {

    private static final String BEARER = "Bearer ";

    private final List<Registered> registered;
    private final String sharedToken;

    DeviceTokenVerifier(AccessPolicy policy) {
        this.registered = parse(policy.devices());
        String shared = policy.deviceToken();
        this.sharedToken = shared == null || shared.isBlank() ? null : shared;
        if (sharedToken != null && registered.stream().anyMatch(r -> r.token().equals(sharedToken))) {
            // 같은 값이면 방 묶기가 먼저 걸려 다른 방의 옛 장치가 403이 된다. 설정 실수라 띄우지 않는다
            throw new IllegalStateException("MSPACE_DEVICES에 예전 공용 토큰(MSPACE_DEVICE_TOKEN)과 같은 토큰이 있습니다");
        }
    }

    /**
     * 토큰이 맞고 그 방에 쓸 수 있는지 확인한다. 토큰은 시간 차 없이 비교한다(MessageDigest.isEqual).
     * 장치별 토큰이면 요청의 deviceId도 그 토큰으로 등록된 이름 중 하나여야 한다(기록이 다른 장치 이름으로 남지 않게).
     * 보드 하나가 센서·키패드를 같이 보내는 경우(ESP32 sensor-2·keypad-2)를 위해 같은 방 장치끼리는 토큰을 같이 쓸 수 있다.
     * roomId·deviceId가 없으면(형식 오류이거나 GET) 그 값은 건너뛰고, 400은 부르는 쪽이 낸다.
     */
    public void verify(String authorization, Integer roomId, String deviceId) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new BusinessException(ErrorCode.DEVICE_UNAUTHORIZED);
        }
        byte[] given = authorization.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        Integer boundRoom = null;
        boolean deviceMatched = false;
        for (Registered r : registered) {
            if (MessageDigest.isEqual(given, r.token().getBytes(StandardCharsets.UTF_8))) {
                boundRoom = r.roomId();
                deviceMatched |= r.deviceId().equals(deviceId);
            }
        }
        if (boundRoom != null) {
            if (roomId != null && !boundRoom.equals(roomId)) {
                throw new BusinessException(ErrorCode.DEVICE_ROOM_MISMATCH);
            }
            if (deviceId != null && !deviceMatched) {
                throw new BusinessException(ErrorCode.DEVICE_ID_MISMATCH);
            }
            return;
        }
        if (sharedToken != null && MessageDigest.isEqual(given, sharedToken.getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        throw new BusinessException(ErrorCode.DEVICE_UNAUTHORIZED);
    }

    /** 형식이 틀리면 서버를 띄우지 않는다(조용히 401만 나면 원인을 찾기 어려워서) */
    static List<Registered> parse(String devices) {
        List<Registered> out = new ArrayList<>();
        if (devices == null || devices.isBlank()) {
            return out;
        }
        Set<String> ids = new HashSet<>();
        Map<String, Integer> roomOfToken = new HashMap<>();
        for (String entry : devices.split(",", -1)) {
            String[] parts = entry.trim().split(":", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
                throw new IllegalStateException("MSPACE_DEVICES 형식은 deviceId:roomId:토큰 입니다 (" + (out.size() + 1) + "번째)");
            }
            int roomId;
            try {
                roomId = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("MSPACE_DEVICES의 roomId가 숫자가 아닙니다: " + parts[0].trim(), e);
            }
            String deviceId = parts[0].trim();
            String token = parts[2].trim();
            if (!ids.add(deviceId)) {
                throw new IllegalStateException("MSPACE_DEVICES에 같은 장치가 두 번 있습니다: " + deviceId);
            }
            Integer before = roomOfToken.putIfAbsent(token, roomId);
            if (before != null && before != roomId) {
                throw new IllegalStateException("MSPACE_DEVICES에서 한 토큰이 다른 방 장치에 같이 쓰였습니다: " + deviceId);
            }
            out.add(new Registered(deviceId, roomId, token));
        }
        return List.copyOf(out);
    }

    record Registered(String deviceId, int roomId, String token) {
    }
}
