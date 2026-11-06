package kr.ac.chosun.mspace.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 장치(키패드·센서)가 보내는 Authorization: Bearer 장치 토큰 확인.
 * 지금은 토큰 하나(mspace.access.device-token)를 같이 쓴다. 장치마다 토큰·방을 등록하는 방식은 재웅 답 대기.
 */
@Component
public class DeviceTokenVerifier {

    private final AccessPolicy policy;

    DeviceTokenVerifier(AccessPolicy policy) {
        this.policy = policy;
    }

    /** 토큰은 길이·내용을 시간 차 없이 비교한다(MessageDigest.isEqual). 설정이 비어 있으면 항상 401 */
    public void verify(String authorization) {
        String expected = policy.deviceToken();
        if (expected == null || expected.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.DEVICE_UNAUTHORIZED);
        }
        byte[] given = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.DEVICE_UNAUTHORIZED);
        }
    }
}
