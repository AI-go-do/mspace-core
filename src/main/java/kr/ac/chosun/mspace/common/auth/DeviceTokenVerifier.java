package kr.ac.chosun.mspace.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    }

    /**
     * 토큰이 맞고 그 방에 쓸 수 있는지 확인한다. 토큰은 시간 차 없이 비교한다(MessageDigest.isEqual).
     * roomId가 없으면(형식 오류) 토큰만 확인하고, 400은 부르는 쪽이 낸다.
     */
    public void verify(String authorization, Integer roomId) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new BusinessException(ErrorCode.DEVICE_UNAUTHORIZED);
        }
        byte[] given = authorization.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        Registered found = null;
        for (Registered r : registered) {
            if (MessageDigest.isEqual(given, r.token().getBytes(StandardCharsets.UTF_8))) {
                found = r;
            }
        }
        if (found != null) {
            if (roomId != null && found.roomId() != roomId) {
                throw new BusinessException(ErrorCode.DEVICE_ROOM_MISMATCH);
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
        Set<String> tokens = new HashSet<>();
        for (String entry : devices.split(",")) {
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
            if (!ids.add(deviceId) || !tokens.add(token)) {
                throw new IllegalStateException("MSPACE_DEVICES에 같은 장치나 같은 토큰이 두 번 있습니다: " + deviceId);
            }
            out.add(new Registered(deviceId, roomId, token));
        }
        return List.copyOf(out);
    }

    record Registered(String deviceId, int roomId, String token) {
    }
}
