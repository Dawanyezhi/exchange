package com.laser.exchange.resultpublisher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ResultPublisherApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResultPublisherApplication.class, args);
    }
}
