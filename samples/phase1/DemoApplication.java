package com.example.demo;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DemoApplication {

    private final Environment env;

    public DemoApplication(Environment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/")
    public String hello() {
        return "Hello from Spring Boot!";
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.ofEntries(
            Map.entry("service", env.getProperty("spring.application.name", "unknown")),
            Map.entry("APP_ENV", env.getProperty("APP_ENV", "undefined")),
            Map.entry("APP_VERSION", env.getProperty("APP_VERSION", "undefined")),
            Map.entry("LOG_LEVEL", env.getProperty("LOG_LEVEL", "undefined")),
            Map.entry("CACHE_TTL", env.getProperty("CACHE_TTL", "undefined"))
        );
    }
}
