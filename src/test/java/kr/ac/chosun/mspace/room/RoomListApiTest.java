package kr.ac.chosun.mspace.room;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

/** FR-ROOM-001 방 목록 조회. 테스트마다 롤백해서 is_active 변경이 다른 테스트에 남지 않는다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class RoomListApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("방 5개가 번호 순으로 이름·최소·최대 인원과 함께 나온다")
    void allRooms() {
        assertThat(mvc.get().uri("/api/rooms"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(5);
                    json.assertThat().extractingPath("$[0].id").isEqualTo(1);
                    json.assertThat().extractingPath("$[0].name").isEqualTo("1번 방");
                    json.assertThat().extractingPath("$[0].minPeople").isEqualTo(3);
                    json.assertThat().extractingPath("$[0].maxPeople").isEqualTo(5);
                    json.assertThat().extractingPath("$[4].minPeople").isEqualTo(5);
                    json.assertThat().extractingPath("$[4].maxPeople").isEqualTo(8);
                });
    }

    @Test
    @DisplayName("수용 기준: 방 5개 중 1개 사용 불가면 목록은 4개")
    void inactiveRoomHidden() {
        // given
        jdbc.sql("UPDATE rooms SET is_active = false WHERE id = 3").update();

        // then
        assertThat(mvc.get().uri("/api/rooms"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(4);
                    json.assertThat().extractingPath("$[*].id").asArray().containsExactly(1, 2, 4, 5);
                });
    }
}
