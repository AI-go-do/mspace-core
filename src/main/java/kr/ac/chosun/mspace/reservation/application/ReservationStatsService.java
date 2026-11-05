package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.domain.CreatedVia;
import org.springframework.stereotype.Service;

/**
 * WEB/AGENT 예약 건수 (11/3 영준 요청: 비서 결과 숫자용). 날짜는 서울 기준으로 자른다.
 * 조회할 때만 GROUP BY 한 번이라, 예약 만들기·키패드 같은 평소 길에는 아무 영향이 없다.
 */
@Service
class ReservationStatsService implements ReservationStatsUseCase {

    private final ReservationStatsPort port;

    ReservationStatsService(ReservationStatsPort port) {
        this.port = port;
    }

    @Override
    public Counts count(long userId, LocalDate from, LocalDate to) {
        // 운영 숫자라 학생에게는 열지 않는다 (11/3 지영 결정: 관리자만)
        if (!port.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        // 아주 먼 날짜(+999999999년 등)는 plusDays·timestamptz 범위에서 500이 나서 입구에서 400으로 막는다
        requireSaneYear(from, "from");
        requireSaneYear(to, "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from은 to보다 늦을 수 없습니다");
        }
        // to는 그날 끝까지 포함 → 다음 날 0시 전까지
        Instant start = from == null ? null : from.atStartOfDay(SEOUL).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(SEOUL).toInstant();
        Map<CreatedVia, Long> counts = port.countByCreatedVia(start, end);
        long web = counts.getOrDefault(CreatedVia.WEB, 0L);
        long agent = counts.getOrDefault(CreatedVia.AGENT, 0L);
        return new Counts(from, to, web + agent, web, agent);
    }

    /** 운영 기간 근처만 의미가 있어 넉넉히 2000–2100년만 받는다 */
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;

    private static void requireSaneYear(LocalDate date, String name) {
        if (date != null && (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    name + "는 " + MIN_YEAR + "–" + MAX_YEAR + "년 사이여야 합니다");
        }
    }
}
