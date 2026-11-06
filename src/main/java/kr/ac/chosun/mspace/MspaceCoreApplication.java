package kr.ac.chosun.mspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MspaceCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(MspaceCoreApplication.class, args);
    }
}
