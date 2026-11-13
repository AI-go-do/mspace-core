package kr.ac.chosun.mspace.ops.application;

import java.time.Instant;

/**
 * 다른 기능이 스스로 처리한 일을 "AI가 처리한 일"(ops_action)에 남기는 입구 (11/13 지영: 불 켜짐도 AI 칸에).
 * 같은 (kind, roomId, startedAt)은 한 번만 남는다. 부르는 쪽 트랜잭션 안에서 부르면 알림 저장과 같이 묶인다.
 */
public interface OpsActionRecorder {

    /**
     * @param kind      처리 종류 (예: LIGHT_LEFT_ON 불 켜짐). DB CHECK에 있는 값만
     * @param startedAt 같은 일을 한 번만 남기는 기준 시각 (불 켜짐은 처음 켜짐을 본 시각)
     * @param detail    무엇을 보고 정했나. 숫자만, 이름·학번 없이 200자 안
     * @return 새로 남겼으면 true, 이미 있으면 false
     */
    boolean record(String kind, int roomId, Long reservationId, Instant startedAt, Instant now, String detail);

    /** 남긴 일에 알림 보낸 사람 수를 적는다 */
    void notified(String kind, int roomId, Instant startedAt, int count);
}
