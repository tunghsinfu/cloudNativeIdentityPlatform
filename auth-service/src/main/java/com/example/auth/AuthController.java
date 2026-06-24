package com.example.auth;

import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JwtUtil jwt;

    public AuthController(JdbcTemplate jdbc, StringRedisTemplate redis, JwtUtil jwt) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.jwt = jwt;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestParam String username,
                                        @RequestParam String password) {
        try {
            jdbc.update("INSERT INTO users (username, password) VALUES (?, ?)",
                        username, password);
            return Map.of("status", "ok", "message", "user created");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                     @RequestParam String password) {
        var users = jdbc.query(
            "SELECT password FROM users WHERE username = ?",
            (rs, row) -> rs.getString("password"), username);

        if (users.isEmpty() || !users.getFirst().equals(password)) {
            return Map.of("status", "error", "message", "invalid credentials");
        }

        String token = jwt.generateToken(username);
        return Map.of("status", "ok", "token", token);
    }

    @GetMapping("/verify")
    public Map<String, Object> verify(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Map.of("status", "error", "message", "missing token");
        }
        String token = authHeader.substring(7);

        if (Boolean.TRUE.equals(redis.hasKey("blacklist:" + token))) {
            return Map.of("status", "error", "message", "token revoked");
        }

        String username = jwt.validateAndGetUsername(token);
        if (username == null) {
            return Map.of("status", "error", "message", "invalid token");
        }
        return Map.of("status", "ok", "username", username);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Map.of("status", "error", "message", "missing token");
        }
        String token = authHeader.substring(7);
        redis.opsForValue().set("blacklist:" + token, "1");
        return Map.of("status", "ok", "message", "logged out");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String pg = "FAIL";
        String r = "FAIL";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            pg = "OK";
        } catch (Exception e) { pg = e.getMessage(); }
        try {
            redis.opsForValue().set("auth-ping", "pong");
            r = "OK";
        } catch (Exception e) { r = e.getMessage(); }
        return Map.of("service", "auth-service", "postgresql", pg, "redis", r);
    }
}
