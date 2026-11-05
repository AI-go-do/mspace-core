package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.reservation.application.CancelReservationUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 내 예약(FR-RSV-006)과 시작 전 취소(FR-RSV-007).
 * 취소의 "시작 전"은 DB 시각(now())으로 판단하므로, 예약은 실제 지금 기준으로 앞뒤 시각에 SQL로 직접 넣는다
 * (고정 Clock을 쓰면 테스트를 돌리는 날짜에 따라 결과가 바뀐다).
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyReservationsAndCancelApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    @Autowired
    CancelReservationUseCase cancels;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        String[] names = {"김지영", "이영준", "박수연", "최재웅", "남궁민수", "정하", "홍길동"};
        for (int i = 0; i < names.length; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, :n, now())")
                    .param("e", "u" + (i + 1) + "@chosun.ac.kr").param("s", "2023000" + (i + 1))
                    .param("n", names[i]).update();
        }
    }

    /**
     * 예약 한 건과 참여자를 한 트랜잭션으로 넣는다 (인원 확인 트리거가 커밋 때 돈다).
     *
     * @param hoursFromNow 시작 시각 = 이번 정각 + 이 시간. 1시간짜리 예약
     * @param people       첫 번째가 대표자
     */
    long reservation(int roomId, int hoursFromNow, String status, long... people) {
        return tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at, checked_out_at, canceled_at)
                            SELECT :room, :owner, t, t + interval '1 hour', :n, '123456', :status,
                                   CASE WHEN :status IN ('IN_USE', 'CHECKED_OUT', 'AUTO_CHECKED_OUT') THEN t END,
                                   CASE WHEN :status IN ('CHECKED_OUT', 'AUTO_CHECKED_OUT') THEN t + interval '1 hour' END,
                                   CASE WHEN :status = 'CANCELED' THEN t - interval '1 hour' END
                            FROM (SELECT date_trunc('hour', now()) + make_interval(hours => :h) AS t) x
                            RETURNING id
                            """)
                    .param("room", roomId).param("owner", people[0]).param("n", people.length)
                    .param("status", status).param("h", hoursFromNow)
                    .query(Long.class).single();
            for (long uid : people) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                VALUES (:rid, :uid, :owner, now(), now() + interval '1 hour')
                                """)
                        .param("rid", id).param("uid", uid).param("owner", uid == people[0]).update();
            }
            return id;
        });
    }

    MvcTestResult mine(long userId) {
        return mvc.get().uri("/api/reservations/me").header("X-User-Id", String.valueOf(userId)).exchange();
    }

    MvcTestResult cancel(long userId, long reservationId) {
        return mvc.post().uri("/api/reservations/{id}/cancel", reservationId)
                .header("X-User-Id", String.valueOf(userId)).exchange();
    }

    String status(long reservationId) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", reservationId)
                .query(String.class).single();
    }

    @Test
    @DisplayName("수용 기준: 내 예약은 대표자·참여자 예약 모두, 끝나지 않은 것(시작 빠른 순) → 끝난 것(최근 순)")
    void listOrder() {
        long later = reservation(2, 72, "BOOKED", 4, 2, 5);           // 사흘 뒤, 2번은 참여자
        long sooner = reservation(1, 24, "BOOKED", 1, 2, 3);          // 내일, 2번은 참여자
        long oldDone = reservation(3, -72, "CANCELED", 4, 2, 5);      // 사흘 전 취소
        long recentDone = reservation(3, -24, "CHECKED_OUT", 6, 2, 3); // 어제 퇴실
        reservation(4, 48, "BOOKED", 7, 3, 5);                        // 2번이 없는 예약은 안 나온다

        assertThat(mine(2)).hasStatusOk().bodyJson()
                .extractingPath("$[*].id")
                .asArray().containsExactly((int) sooner, (int) later, (int) recentDone, (int) oldDone);
        assertThat(mine(2)).bodyJson().extractingPath("$[2].status").isEqualTo("CHECKED_OUT");
    }

    @Test
    @DisplayName("내 예약에 입실 마감(checkinDeadline)을 DB 값 그대로 준다: 미리 예약은 시작 + 15분")
    void checkinDeadline() {
        long id = reservation(1, 24, "BOOKED", 1, 2, 3); // 1번 방은 3~5명
        String expected = jdbc.sql("""
                        SELECT to_char(checkin_deadline AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS"+09:00"')
                        FROM reservation WHERE id = :id AND checkin_deadline = start_at + interval '15 minutes'
                        """)
                .param("id", id).query(String.class).single();

        assertThat(mine(1)).hasStatusOk().bodyJson()
                .extractingPath("$[0].checkinDeadline").isEqualTo(expected);
        assertThat(mine(2)).bodyJson()
                .extractingPath("$[0].checkinDeadline").isEqualTo(expected);
    }

    @Test
    @DisplayName("수용 기준: 키패드 번호는 대표자에게만, 참여자에게는 null. 참여자 이름은 가린다(김*영)")
    void accessCodeOnlyForOwner() {
        reservation(1, 24, "BOOKED", 1, 2, 5);

        assertThat(mine(1)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].owner").isEqualTo(true);
            json.assertThat().extractingPath("$[0].accessCode").isEqualTo("123456");
            json.assertThat().extractingPath("$[0].room.name").isEqualTo("1번 방");
            json.assertThat().extractingPath("$[0].participants[*].name").asArray()
                    .containsExactly("김*영", "이*준", "남**수");
            json.assertThat().extractingPath("$[0].participants[0].owner").isEqualTo(true);
        });
        assertThat(mine(2)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].owner").isEqualTo(false);
            json.assertThat().extractingPath("$[0].accessCode").isNull();
        });
        assertThat(mine(7)).hasStatusOk().bodyJson().extractingPath("$").asArray().isEmpty();
    }

    @Test
    @DisplayName("수용 기준: 시작 전 대표자 취소 → 200 CANCELED, 방·참여자 시간이 바로 풀린다")
    void ownerCancelsBeforeStart() {
        long id = reservation(1, 24, "BOOKED", 1, 2, 3);

        assertThat(cancel(1, id)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.id").isEqualTo((int) id);
            json.assertThat().extractingPath("$.status").isEqualTo("CANCELED");
        });
        assertThat(status(id)).isEqualTo("CANCELED");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation_participant WHERE reservation_id = :id AND active")
                .param("id", id).query(Integer.class).single()).isZero();
        // 같은 방·같은 시간·같은 사람으로 다시 예약할 수 있다 (겹침 제약에서 빠짐)
        reservation(1, 24, "BOOKED", 1, 2, 3);
    }

    @Test
    @DisplayName("수용 기준: 참여자가 취소 → 403 NOT_RESERVATION_OWNER, 예약은 그대로")
    void participantCannotCancel() {
        long id = reservation(1, 24, "BOOKED", 1, 2, 3);

        assertThat(cancel(2, id)).hasStatus(403).bodyJson().extractingPath("$.code")
                .isEqualTo("NOT_RESERVATION_OWNER");
        assertThat(status(id)).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("없는 예약, 내가 들어 있지 않은 남의 예약 → 둘 다 404 (남의 예약이 있는지 알려 주지 않는다)")
    void notFound() {
        long id = reservation(1, 24, "BOOKED", 1, 2, 3);

        assertThat(cancel(7, id)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(cancel(1, 999)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(status(id)).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("수용 기준: 시작 후 취소 → 409. 입실 전(BOOKED)·사용 중·이미 취소 모두, 이유는 메시지로")
    void notCancelable() {
        long started = reservation(1, -1, "BOOKED", 1, 2, 3);   // 한 시간 전 시작, 아직 입실 안 함(노쇼 판정 전)
        long inUse = reservation(2, 0, "IN_USE", 4, 5, 6);
        long canceled = reservation(3, 24, "CANCELED", 7, 2, 3);

        assertThat(cancel(1, started)).hasStatus(409).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.code").isEqualTo("RESERVATION_NOT_CANCELABLE");
            json.assertThat().extractingPath("$.message").isEqualTo("이미 시작 시각이 지난 예약은 취소할 수 없습니다");
        });
        assertThat(cancel(4, inUse)).hasStatus(409).bodyJson().extractingPath("$.message")
                .isEqualTo("사용 중인 예약은 취소할 수 없습니다. 퇴실해 주세요");
        assertThat(cancel(7, canceled)).hasStatus(409).bodyJson().extractingPath("$.message")
                .isEqualTo("이미 취소된 예약입니다");
        assertThat(status(started)).isEqualTo("BOOKED");
        assertThat(status(inUse)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("같은 취소를 동시에 10번 → 1번만 성공, 9번은 409 (조건부 UPDATE라 멱등 키 없이도 한 번만 처리)")
    void concurrentCancel() throws Exception {
        long id = reservation(1, 24, "BOOKED", 1, 2, 3);
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        Map<String, Integer> results = new ConcurrentHashMap<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        go.await();
                        cancels.cancel(id, 1);
                        results.merge("ok", 1, Integer::sum);
                    } catch (BusinessException e) {
                        results.merge(e.errorCode().name(), 1, Integer::sum);
                    } catch (Exception e) {
                        results.merge("other", 1, Integer::sum);
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).isEqualTo(Map.of("ok", 1, "RESERVATION_NOT_CANCELABLE", 9));
        assertThat(status(id)).isEqualTo("CANCELED");
    }
}
