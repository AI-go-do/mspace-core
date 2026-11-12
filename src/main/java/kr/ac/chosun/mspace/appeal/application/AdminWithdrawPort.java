package kr.ac.chosun.mspace.appeal.application;

import java.time.Instant;
import java.util.Optional;

/** 관리자 경고 철회 저장소 (V33). 구현: JdbcAdminWithdrawAdapter */
public interface AdminWithdrawPort {

    boolean isAdmin(long userId);

    /** 경고 한 줄. withdrawnAt이 있으면 이미 철회된 경고 */
    record Warning(long warningId, long userId, String reason, Long reservationId, Instant withdrawnAt) {
    }

    /** 경고를 읽는다 (잠그지 않음. 철회는 withdrawByAdmin의 조건부 UPDATE가 한 번만 되게 막는다). 없으면 비어 있다 */
    Optional<Warning> find(long warningId);

    /** 그 경고의 대기 중인 철회 요청 id (없으면 비어 있다) */
    Optional<Long> pendingAppealOf(long warningId);

    /** 철회 시각과 함께 누가·왜를 남긴다. @return 아직 철회 전이라 바꿨으면 true (이미 철회됐으면 false) */
    boolean withdrawByAdmin(long warningId, long adminId, String reason, Instant now);
}
