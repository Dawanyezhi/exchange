package com.laser.exchange.resultpublisher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class ResultPublisherApplicationTest {

    @Test
    void contextLoads() {
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(ResultPublisherApplication.class)
                .web(WebApplicationType.NONE)
                .run("--laser.result-publisher.enabled=false")) {
        }
    }
}
