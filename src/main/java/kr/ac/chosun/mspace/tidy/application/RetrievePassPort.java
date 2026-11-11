package kr.ac.chosun.mspace.tidy.application;

import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.tidy.application.RetrievePassUseCase.Pass;

/** 표는 V27__retrieve_pass.sql. 구현: JdbcRetrievePassAdapter */
public interface RetrievePassPort {

    /** 그 예약 줄을 잠그고 읽는다 (같은 예약에 두 사람이 동시에 눌러도 QR이 하나만 생기게). 내 예약이 아니면 빈 값 */
    Optional<Ended> lockMine(long reservationId, long userId);

    /** @param checkedOutAt 퇴실 시각. 입실 안 하고 끝난 예약은 null */
    record Ended(int roomId, String status, Instant checkedOutAt) {
    }

    /** 퇴실 직후 확인(FIRST)이 LEFT_ITEMS인지 */
    boolean leftItems(long reservationId);

    /** 그 예약에 now에 살아 있는 QR */
    Optional<Pass> alive(long reservationId, Instant now);

    /** 이 방에서 now에 쓰이는 번호인지 (끝나지 않은 예약 번호, 살아 있는 찾으러 온 QR) */
    boolean codeTaken(int roomId, String code, Instant now);

    void insert(Pass pass, long issuedBy, Instant issuedAt);

    /** 이 방에서 now에 살아 있는 QR. 없으면 빈 값 */
    Optional<Live> find(int roomId, String code, Instant now);

    record Live(long passId, long reservationId) {
    }

    /** 예약 줄을 잠근다. 자동 판정·다시 확인과 같은 잠금이라 QR 사용과 경고가 한 줄로 선다 */
    void lockReservation(long reservationId);

    /** 아직 살아 있으면 처음 쓴 시각을 남긴다. 잠금을 기다리는 동안 닫혔으면 false */
    boolean markUsed(long passId, Instant now);
}
