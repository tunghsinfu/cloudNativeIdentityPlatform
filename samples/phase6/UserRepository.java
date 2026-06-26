package com.example.auth.repository;

import com.example.auth.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
public class UserRepository {

    private static final long CACHE_TTL_SEC = 300;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public UserRepository(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public Optional<User> findByUsername(String username) {
        String cacheKey = "user:" + username;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return Optional.of(MAPPER.readValue(cached, User.class));
            } catch (Exception e) {
                // deserialization failed — fall through to DB
            }
        }
        var rows = jdbc.query(
                "SELECT id, username, password, role FROM users WHERE username = ?",
                (rs, rowNum) -> new User(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("role")
                ),
                username);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        User user = rows.getFirst();
        try {
            redis.opsForValue().set(cacheKey, MAPPER.writeValueAsString(user),
                    Duration.ofSeconds(CACHE_TTL_SEC));
        } catch (Exception e) {
            // cache write failure is non-fatal
        }
        return Optional.of(user);
    }

    public void save(String username, String password, String role) {
        jdbc.update("INSERT INTO users (username, password, role) VALUES (?, ?, ?)",
                username, password, role);
        redis.delete("user:" + username);
        // no cache to delete on insert, but makes the intent explicit
    }
}
