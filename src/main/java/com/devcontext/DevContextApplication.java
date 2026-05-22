package com.devcontext;

import com.devcontext.config.DevContextLlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DevContextLlmProperties.class)
public class DevContextApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevContextApplication.class, args);
    }
}
