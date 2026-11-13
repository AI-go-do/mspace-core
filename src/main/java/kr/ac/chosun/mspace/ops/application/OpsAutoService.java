package kr.ac.chosun.mspace.ops.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/** AI 자동 처리 (11/12 지영 "관리자 카드 다 AI가 처리하게"). 관리자는 처리한 일(ops_action)을 보기만 한다 */
@Service
class OpsAutoService implements OpsAutoUseCase {

    private final OpsAutoPort port;
    private final Clock clock;

    OpsAutoService(OpsAutoPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Override
    public List<OpsAction> actions(long adminId, LocalDate date) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        LocalDate day = date == null ? LocalDate.ofInstant(clock.instant(), SEOUL) : date;
        Instant from = day.atStartOfDay(SEOUL).toInstant();
        return port.actions(from, day.plusDays(1).atStartOfDay(SEOUL).toInstant()).stream()
                .map(r -> new OpsAction(r.id(), r.kind(), r.roomId(), r.roomName(), r.reservationId(),
                        r.startedAt().atZone(SEOUL), r.detectedAt().atZone(SEOUL), r.detail(), r.notified()))
                .toList();
    }
}
