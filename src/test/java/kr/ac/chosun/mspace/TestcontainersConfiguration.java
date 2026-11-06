package kr.ac.chosun.mspace;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 진짜 PostgreSQL 16.
 * {@code @ServiceConnection}이 컨테이너 주소·계정을 datasource 설정에 자동으로 넣어 준다.
 * 테스트 클래스에 {@code @Import(TestcontainersConfiguration.class)}를 붙여 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** 테스트마다 공유하는 PostgreSQL 16 컨테이너. 연결 한도를 200으로 늘려 띄운다 */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // 동시 100건 측정에서 연결 풀을 100개 이상 쓰므로 기본 연결 한도(100)를 늘린다
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withCommand("postgres", "-c", "max_connections=200");
    }
}
