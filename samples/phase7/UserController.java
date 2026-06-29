package com.example.user.controller;

import com.example.user.model.UserProfile;
import com.example.user.repository.UserProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/user/v1")
public class UserController {

    private final UserProfileRepository profileRepository;

    public UserController(UserProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @GetMapping("/profile")
    public UserProfile getProfile(Authentication auth) {
        String username = auth.getName();
        return profileRepository.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "profile not found for user: " + username));
    }

    @PostMapping("/profile")
    public Map<String, Object> createOrUpdateProfile(Authentication auth,
                                                      @RequestParam(required = false) String displayName,
                                                      @RequestParam(required = false) String email,
                                                      @RequestParam(required = false) String avatarUrl,
                                                      @RequestParam(required = false) String bio) {
        String username = auth.getName();
        var existing = profileRepository.findByUsername(username);
        if (existing.isPresent()) {
            profileRepository.update(username, displayName, email, avatarUrl, bio);
            return Map.of("status", "ok", "message", "profile updated");
        } else {
            profileRepository.save(username, displayName, email, avatarUrl, bio);
            return Map.of("status", "ok", "message", "profile created");
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "user-service");
    }
}
