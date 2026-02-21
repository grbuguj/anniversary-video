package com.anniversary.video;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AnniversaryVideoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnniversaryVideoApplication.class, args);
    }
}
