package kr.ac.chosun.mspace.appeal.application;

import java.time.Instant;

/**
 * 관리자 경고 철회 (11/12 지영). 학생 철회 요청이 AI로 철회될 때와 같은 일을 한다:
 * 경고를 세지 않게 하고, 그 경고로 걸린 정지를 지금 풀고, 학생에게 알린다. 대기 중인 철회 요청은 철회로 닫는다.
 * 다른 점은 누가·왜 풀었는지(관리자 id·이유)를 경고에 남기는 것 (무인 운영이라 사람이 끼어든 건 구분해 둔다)
 */
public interface AdminWithdrawUseCase {

    int REASON_MAX = 200;

    /**
     * @param reason 필수, 1–200자(글자 수, 이모지도 1자). 학생 알림에도 붙는다
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 관리자가 아니면 403 ADMIN_ONLY, 경고가 없으면
     *         404 WARNING_NOT_FOUND, 이미 철회됐으면 409 WARNING_ALREADY_WITHDRAWN, 이유가 비거나 길면 400
     */
    Withdrawn withdraw(long adminId, long warningId, String reason);

    /**
     * @param suspensionLifted 이 경고로 걸린 정지를 풀었으면 true
     * @param appealClosed 대기 중이던 학생 철회 요청을 철회로 닫았으면 true
     */
    record Withdrawn(long warningId, long userId, Instant withdrawnAt, boolean suspensionLifted, boolean appealClosed) {
    }
}
