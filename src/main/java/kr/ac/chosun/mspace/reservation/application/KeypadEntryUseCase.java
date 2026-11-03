package kr.ac.chosun.mspace.reservation.application;

/**
 * 키패드 번호로 예약을 찾아 문을 열지 정하는 입력 포트 (FR-ACC-001). access 기능이 부른다.
 * 구현: {@link KeypadEntryService}
 */
public interface KeypadEntryUseCase {

    /**
     * 처음 맞으면 BOOKED → IN_USE(체크인), 이미 IN_USE면 문만 연다.
     *
     * @param roomId 키패드가 붙은 방
     * @param code   입력한 6자리 번호
     */
    Entry enter(int roomId, String code);

    /**
     * @param result        OPENED, NOT_YET(맞는 번호지만 시작 10분 전보다 이름), WRONG_CODE(없는 번호·다른 방·끝난 예약)
     * @param reservationId 찾은 예약. WRONG_CODE면 null
     */
    record Entry(Result result, Long reservationId) {
    }

    enum Result {
        OPENED, NOT_YET, WRONG_CODE
    }
}
