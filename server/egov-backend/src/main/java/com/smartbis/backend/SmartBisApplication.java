package com.smartbis.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartBisApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartBisApplication.class, args);
    }
}
