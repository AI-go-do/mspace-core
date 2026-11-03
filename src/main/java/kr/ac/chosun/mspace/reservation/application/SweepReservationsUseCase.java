package kr.ac.chosun.mspace.reservation.application;

/**
 * 사람 없이 돌아가게 하는 정리 (FR-RSV-101 자동 퇴실, FR-RSV-102 노쇼). 스케줄러가 1분마다 부른다.
 */
public interface SweepReservationsUseCase {

    /** 지금 시각으로 노쇼·자동 퇴실을 한 번 처리한다. 몇 건 바꿨는지 돌려준다 */
    Swept sweep();

    record Swept(int noShows, int autoCheckedOut) {
    }
}
