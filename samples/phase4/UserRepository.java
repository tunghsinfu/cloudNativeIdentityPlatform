package com.example.auth.repository;

import com.example.auth.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        var rows = jdbc.query(
                "SELECT id, username, password, role FROM users WHERE username = ?",
                (rs, rowNum) -> new User(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("role")
                ),
                username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void save(String username, String password, String role) {
        jdbc.update("INSERT INTO users (username, password, role) VALUES (?, ?, ?)",
                username, password, role);
    }
}
