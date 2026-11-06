package kr.ac.chosun.mspace.notification.application;

/** 예약 알림 3종 (FR-NTF-101 입실 전, 102 종료 전, 104 노쇼 전). 스케줄러가 1분마다 부른다 */
public interface SendRemindersUseCase {

    /** 지금 시각으로 보낼 알림을 한 번 처리한다. 새로 만든 알림 건수 */
    int sendDue();
}
