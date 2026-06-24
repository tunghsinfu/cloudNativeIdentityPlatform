package com.example.demo;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DemoApplication {

    private final Environment env;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public DemoApplication(Environment env, JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.env = env;
        this.jdbc = jdbc;
        this.redis = redis;
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
            Map.entry("CACHE_TTL", env.getProperty("CACHE_TTL", "undefined")),
            Map.entry("cache_ttl_source", isEnvSet("APP_CACHE_TTL")
                ? "from .env (APP_CACHE_TTL)" : "from docker-compose default")
        );
    }

    private boolean isEnvSet(String key) {
        String val = env.getProperty(key);
        return val != null && !val.isEmpty();
    }

    @GetMapping("/db-check")
    public Map<String, Object> dbCheck() {
        String pgStatus = "FAIL";
        String redisStatus = "FAIL";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            pgStatus = "OK";
        } catch (Exception e) {
            pgStatus = e.getMessage();
        }
        try {
            redis.opsForValue().set("ping", "pong");
            String pong = redis.opsForValue().get("ping");
            redisStatus = "OK (ping=" + pong + ")";
        } catch (Exception e) {
            redisStatus = e.getMessage();
        }
        return Map.of("postgresql", pgStatus, "redis", redisStatus);
    }
}
