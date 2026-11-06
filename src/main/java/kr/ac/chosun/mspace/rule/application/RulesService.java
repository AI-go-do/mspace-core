package kr.ac.chosun.mspace.rule.application;

import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.config.ExtensionPolicy;
import kr.ac.chosun.mspace.common.config.NoShowPenaltyPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.config.RulesProperties;
import org.springframework.stereotype.Service;

/**
 * 이용 규칙 (FR-WEB-003). 예약 규칙 숫자는 실제 검사에 쓰는 설정값을 그대로 내려 줘서,
 * 설정을 바꾸면 화면·비서 안내도 같이 바뀐다 (따로 적어 두면 어긋나기 쉬워서).
 * 이용 수칙 문구 속 숫자도 {noShowMinutes}처럼 자리만 적어 두고 여기서 설정값으로 채운다.
 */
@Service
public class RulesService {

    private final Rules rules;

    public RulesService(ReservationPolicy reservation, ExtensionPolicy extension, AccessPolicy access,
                        NoShowPenaltyPolicy penalty, RulesProperties props) {
        Map<String, Integer> numbers = Map.of(
                "noShowMinutes", props.noShowMinutes(),
                "instantNoShowMinutes", props.instantNoShowMinutes(),
                "penaltyCount", penalty.count(),
                "penaltyWindowDays", penalty.windowDays(),
                "suspendDays", penalty.suspendDays());
        List<RulesProperties.Conduct> conduct = props.conduct().stream()
                .map(c -> new RulesProperties.Conduct(c.key(), c.title(), fill(c.rule(), numbers),
                        fill(c.penalty(), numbers)))
                .toList();
        this.rules = new Rules(
                new ReservationRules(reservation.bookingWindowDays(), reservation.slotMinutes(),
                        reservation.maxMinutes(), access.openBeforeMinutes(), props.noShowMinutes(),
                        props.instantNoShowMinutes(),
                        new ExtensionRules(extension.maxCount(), extension.openBeforeMinutes(), extension.maxMinutes(),
                                extension.chainGapMinutes())),
                conduct, props.reminders());
    }

    /** {이름} 자리를 설정값으로 채운다. 모르는 이름이 남으면 문구 오타라 시작할 때 멈춘다 */
    static String fill(String text, Map<String, Integer> numbers) {
        String out = text;
        for (Map.Entry<String, Integer> e : numbers.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        // 닫히지 않은 자리({noShowMinutes 처럼)도 잡는다: 채운 뒤 중괄호가 하나라도 남으면 설정 오류
        if (out.indexOf('{') >= 0 || out.indexOf('}') >= 0) {
            throw new IllegalStateException("이용 수칙 문구에 채우지 못한 자리가 있습니다: " + out);
        }
        return out;
    }

    public Rules rules() {
        return rules;
    }

    /**
     * @param reservation 예약 규칙 숫자
     * @param conduct     이용 수칙 표
     * @param reminders   필요한 순간의 한 줄 안내
     */
    public record Rules(ReservationRules reservation, List<RulesProperties.Conduct> conduct,
                        RulesProperties.Reminders reminders) {
    }

    /**
     * @param bookingWindowDays        오늘 포함 며칠 안에 시작하는 예약만 (7)
     * @param slotMinutes              예약 단위 (30)
     * @param maxMinutes               1회 최대 (120)
     * @param checkinOpenBeforeMinutes 시작 몇 분 전부터 입실 번호가 맞나 (10)
     * @param noShowMinutes            미리 예약 입실 기한 (15)
     * @param instantNoShowMinutes     즉시 시작 입실 기한 (10)
     */
    public record ReservationRules(int bookingWindowDays, int slotMinutes, int maxMinutes, int checkinOpenBeforeMinutes,
                                   int noShowMinutes, int instantNoShowMinutes, ExtensionRules extension) {
    }

    /**
     * @param maxCount          이어진 사용 한 번에 연장 몇 번 (1)
     * @param openBeforeMinutes 종료 몇 분 전부터 (10)
     * @param maxMinutes        한 번에 늘리는 최대 (120)
     * @param chainGapMinutes   연장 뒤 몇 분 안의 새 예약을 이어진 사용으로 보나 (30)
     */
    public record ExtensionRules(int maxCount, int openBeforeMinutes, int maxMinutes, int chainGapMinutes) {
    }
}
