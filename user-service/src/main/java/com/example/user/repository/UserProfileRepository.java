package com.example.user.repository;

import com.example.user.model.UserProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserProfileRepository {

    private final JdbcTemplate jdbc;

    public UserProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserProfile> findByUsername(String username) {
        var rows = jdbc.query(
            "SELECT id, username, display_name, email, avatar_url, bio FROM user_profiles WHERE username = ?",
            (rs, rowNum) -> new UserProfile(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("avatar_url"),
                rs.getString("bio")
            ),
            username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void save(String username, String displayName, String email, String avatarUrl, String bio) {
        jdbc.update(
            "INSERT INTO user_profiles (username, display_name, email, avatar_url, bio) VALUES (?, ?, ?, ?, ?)",
            username, displayName, email, avatarUrl, bio);
    }

    public void update(String username, String displayName, String email, String avatarUrl, String bio) {
        jdbc.update(
            "UPDATE user_profiles SET display_name = ?, email = ?, avatar_url = ?, bio = ?, updated_at = CURRENT_TIMESTAMP WHERE username = ?",
            displayName, email, avatarUrl, bio, username);
    }
}
