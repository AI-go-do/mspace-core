package kr.ac.chosun.mspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MspaceCoreApplicationTests {

    @Value("${local.server.port}")
    int port;

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
}
