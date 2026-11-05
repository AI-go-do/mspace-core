package kr.ac.chosun.mspace.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이용 규칙 문구와 노쇼 숫자 (FR-WEB-003). 원본은 docs 요구사항 3.1 이용 수칙.
 * 문구를 화면·비서에 박지 않고 여기 한 곳에서 내려 준다.
 *
 * @param noShowMinutes        미리 예약 입실 기한(분). DB 트리거 checkin_deadline과 같아야 한다 (다르면 CheckinDeadlineGuard가 서버를 띄우지 않음)
 * @param instantNoShowMinutes 즉시 시작 입실 기한(분). 위와 같음
 * @param reminders            필요한 순간에 한 줄로 다시 보여 줄 안내
 * @param conduct              이용 수칙 표 (구분·수칙·어기면)
 */
@ConfigurationProperties("mspace.rules")
public record RulesProperties(int noShowMinutes, int instantNoShowMinutes, Reminders reminders, List<Conduct> conduct) {

    /** @param checkin 체크인 직후, @param checkout 퇴실 확인 창 */
    public record Reminders(String checkin, String checkout) {
    }

    public record Conduct(String key, String title, String rule, String penalty) {
    }
}
