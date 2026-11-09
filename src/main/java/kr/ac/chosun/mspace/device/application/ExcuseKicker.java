package kr.ac.chosun.mspace.device.application;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.ac.chosun.mspace.reservation.application.ExcuseNoShowsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 장치 오류를 받은 뒤 노쇼 무르기를 응답과 따로, 바로 돌린다 (11/9 지영 "학생이 1분 기다리는 것도 좀").
 * <ul>
 *   <li>요청은 번호가 맞든 안 맞든 항상 {@link #kick()}만 하고 바로 응답한다 → 응답 시간으로 번호가 맞았는지 알 수 없다</li>
 *   <li>스레드 하나가 차례로 돈다. 기다리는 실행이 이미 있으면 더 쌓지 않는다(요청이 몰려도 무르기는 한 번씩).
 *       실행을 시작할 때 표시를 지우므로, 그 뒤에 커밋된 요청은 다음 실행이 본다</li>
 *   <li>실패하거나 서버가 내려가 못 돌아도 1분 정리가 다시 무른다 (두 겹)</li>
 * </ul>
 */
@Component
class ExcuseKicker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExcuseKicker.class);

    private final ExcuseNoShowsUseCase excuses;
    private final Clock clock;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "device-fault-excuse");
        t.setDaemon(true);
        return t;
    });

    ExcuseKicker(ExcuseNoShowsUseCase excuses, Clock clock) {
        this.excuses = excuses;
        this.clock = clock;
    }

    void kick() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::run);
        } catch (RejectedExecutionException e) {
            pending.set(false); // 서버가 내려가는 중. 1분 정리가 함
        }
    }

    private void run() {
        pending.set(false);
        try {
            excuses.excuse(clock.instant());
        } catch (RuntimeException e) {
            log.warn("장치 오류 노쇼 무르기 실패 (1분 정리가 다시 함): {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        worker.shutdown();
    }
}
