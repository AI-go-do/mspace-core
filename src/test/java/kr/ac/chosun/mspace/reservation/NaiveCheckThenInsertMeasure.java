package kr.ac.chosun.mspace.reservation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비교 측정용 (발표 숫자). DB 제약 없이 서버가 "겹치는 예약이 있나 조회 → 없으면 저장"만 하면 어떻게 되나.
 * 결과가 실행마다 달라서(경쟁 상태) 단언하지 않고 숫자만 찍는다. 기본 테스트에서는 빠지고, 이렇게 따로 돌린다:
 * ./mvnw test -Dtest=NaiveCheckThenInsertMeasure -Dmeasure=true
 */
@EnabledIfSystemProperty(named = "measure", matches = "true")
// 연결 풀 기본값(10)이면 동시에 10개 트랜잭션만 겹쳐서 측정이 줄어든다 → 요청 수보다 크게
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=110")
@Import(TestcontainersConfiguration.class)
class NaiveCheckThenInsertMeasure {

    static final int N = 100;
    static final int ROUNDS = 5;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    /**
     * 실험용 테이블에서 "조회 후 저장"을 동시 100건씩 ROUNDS번 돌리고, 라운드마다 저장된 건수를 찍는다.
     * 1건보다 많으면 중복 예약이 생긴 것이다.
     */
    @Test
    @DisplayName("제약 없는 조회 후 저장: 같은 방·시간 동시 100건 → 몇 건이 저장되나")
    void measure() throws Exception {
        // 겹침 제약이 없는 복사본 테이블 (실험용)
        jdbc.sql("CREATE TABLE IF NOT EXISTS reservation_naive (id serial PRIMARY KEY, room_id int, "
                + "start_at timestamptz, end_at timestamptz)").update();
        Timestamp start = Timestamp.from(Instant.parse("2026-11-04T05:00:00Z"));
        Timestamp end = Timestamp.from(Instant.parse("2026-11-04T07:00:00Z"));

        for (int round = 1; round <= ROUNDS; round++) {
            jdbc.sql("TRUNCATE reservation_naive").update();
            ExecutorService pool = Executors.newFixedThreadPool(N);
            CountDownLatch ready = new CountDownLatch(N);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return tx.execute(status -> {
                        // 1) 조회: 겹치는 예약이 있나
                        int overlap = jdbc.sql("SELECT count(*) FROM reservation_naive WHERE room_id = 1 "
                                        + "AND start_at < :end AND end_at > :start")
                                .param("start", start).param("end", end)
                                .query(Integer.class).single();
                        if (overlap > 0) {
                            return false;
                        }
                        // 2) 저장: 조회와 저장 사이에 다른 요청도 "없음"을 봤을 수 있다
                        jdbc.sql("INSERT INTO reservation_naive (room_id, start_at, end_at) VALUES (1, :start, :end)")
                                .param("start", start).param("end", end).update();
                        return true;
                    });
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Boolean> f : results) {
                f.get();
            }
            pool.shutdown();
            int saved = jdbc.sql("SELECT count(*) FROM reservation_naive").query(Integer.class).single();
            System.out.printf("[비교 측정] 제약 없는 조회 후 저장 %d회차: 같은 방·시간 %d건 저장 (중복 %d건)%n",
                    round, saved, saved - 1);
        }
        jdbc.sql("DROP TABLE reservation_naive").update();
    }
}
