package kr.ac.chosun.mspace.reservation.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kr.ac.chosun.mspace.common.config.RelayPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.room.application.RoomQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class RelayServiceTest {

    @Test
    @DisplayName("넘길게요가 저장된 뒤 다음 사람에게 넘기기가 실패해도 넘길게요는 성공으로 끝난다 (1분 정리가 다시 넘김)")
    void declineSurvivesAdvanceFailure() {
        // given: 넘길게요 저장은 되고, 다음 사람에게 넘기는 트랜잭션은 실패한다
        Instant now = Instant.parse("2026-11-10T01:10:00Z");
        RelayPort port = mock(RelayPort.class);
        when(port.declineOffer(4L, 1L, now)).thenReturn(true);
        TransactionOperations tx = mock(TransactionOperations.class);
        when(tx.execute(any())).thenThrow(new IllegalStateException("db down"));
        RelayService service = new RelayService(port, mock(RoomQuery.class), new RelayPolicy(3, 5, 30),
                new ReservationPolicy(7, 30, 120), mock(NotificationInbox.class), tx,
                Clock.fixed(now, ZoneOffset.UTC));

        // when / then
        assertThatCode(() -> service.decline(4L, 1L)).doesNotThrowAnyException();
        verify(port).declineOffer(4L, 1L, now);
    }
}
