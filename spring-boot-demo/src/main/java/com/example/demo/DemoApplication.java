package com.example.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public Map<String, Object> config() throws IOException {
        String dbPassword = "NOT_SET";
        Path secretPath = Path.of("/run/secrets/db_password");
        if (Files.exists(secretPath)) {
            dbPassword = Files.readString(secretPath).trim();
        }

        return Map.of(
            "APP_ENV", env.getProperty("APP_ENV", "undefined"),
            "APP_VERSION", env.getProperty("APP_VERSION", "undefined"),
            "DB_PASSWORD", dbPassword,
            "secret_source", secretPath.toString()
        );
    }
}
