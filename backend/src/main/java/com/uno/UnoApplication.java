package com.uno;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UnoApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnoApplication.class, args);
    }

}
