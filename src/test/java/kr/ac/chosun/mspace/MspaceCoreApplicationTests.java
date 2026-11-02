package kr.ac.chosun.mspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MspaceCoreApplicationTests {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("서버가 뜨면 /actuator/health가 UP을 돌려준다")
    void healthIsUp() {
        // given
        RestClient client = RestClient.create("http://localhost:" + port);

        // when
        String body = client.get().uri("/actuator/health").retrieve().body(String.class);

        // then
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("Flyway V1이 적용되면 겹침·대표자 1건·같은 사람 두 방 제약이 DB에 생긴다")
    void reservationConstraintsExist() {
        // when
        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conname IN ('no_room_overlap', 'no_person_overlap', 'valid_slot')",
                String.class);
        Integer ownerIndex = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'one_active_per_owner'", Integer.class);

        // then
        assertThat(constraints).containsExactlyInAnyOrder("no_room_overlap", "no_person_overlap", "valid_slot");
        assertThat(ownerIndex).isEqualTo(1);
    }

    @Test
    @DisplayName("방 초기 데이터 5개가 들어간다 (1~4번 3~5명, 5번 5~8명)")
    void roomsSeeded() {
        // when
        Integer rooms = jdbc.queryForObject("SELECT count(*) FROM room", Integer.class);
        Integer bigRoomMax = jdbc.queryForObject("SELECT max_people FROM room WHERE id = 5", Integer.class);

        // then
        assertThat(rooms).isEqualTo(5);
        assertThat(bigRoomMax).isEqualTo(8);
    }
}
