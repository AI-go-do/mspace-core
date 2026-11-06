package kr.ac.chosun.mspace.notification.application;

import java.time.Instant;
import java.util.List;

/** 보낼 때가 된 예약 알림을 찾아 "보냄"으로 기록하고 알림함에 넣는 DB 일. 구현은 adapter/out/persistence */
public interface ReminderPort {

    /**
     * 지금 보낼 알림을 모두 찾아 notification_log에 기록하고(예약·사람·종류별 한 번만), 새로 기록된 것만 알림함에 넣어 돌려준다.
     * 한 트랜잭션이라 기록과 알림함은 같이 되거나 같이 안 된다. 두 번 불러도, 서버 두 대가 같이 불러도 한 번만 나온다
     */
    List<Notification> claimDue(Instant now);
}
