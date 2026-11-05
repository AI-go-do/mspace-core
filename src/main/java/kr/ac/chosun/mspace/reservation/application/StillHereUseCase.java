package kr.ac.chosun.mspace.reservation.application;

/** "아직 있어요" 입력 포트 (FR-DEV-103). 구현: {@link StillHereService} */
public interface StillHereUseCase {

    /** 대표자가 누르면 자리 비움 타이머를 지금부터 다시 잰다. 예약당 1번 */
    void stillHere(long reservationId, long userId);
}
