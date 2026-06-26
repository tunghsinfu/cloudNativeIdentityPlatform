package com.example.auth;

import com.example.auth.handler.RateLimitExceededException;
import com.example.auth.repository.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth/v1")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;

    private static final long REFRESH_TTL_MS = 7 * 24 * 60 * 60 * 1000L;

    public AuthController(UserRepository userRepository,
                          JwtUtil jwtUtil,
                          PasswordEncoder passwordEncoder,
                          StringRedisTemplate redis) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestParam String username,
                                        @RequestParam String password,
                                        @RequestParam(defaultValue = "USER") String role) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("username already exists");
        }
        String hash = passwordEncoder.encode(password);
        userRepository.save(username, hash, role);
        return Map.of("status", "ok", "message", "user registered");
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                     @RequestParam String password) {
        String rateKey = "login_fail:" + username;
        String val = redis.opsForValue().get(rateKey);
        if (val != null && Integer.parseInt(val) >= 5) {
            throw new RateLimitExceededException("too many login attempts, try again later");
        }
        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            incrementRate(rateKey);
            throw new UsernameNotFoundException("invalid username or password");
        }
        var user = userOpt.get();
        if (!passwordEncoder.matches(password, user.password())) {
            incrementRate(rateKey);
            throw new BadCredentialsException("invalid username or password");
        }
        redis.delete(rateKey);
        String accessToken = jwtUtil.generateAccessToken(user.username(), user.role());
        String refreshToken = UUID.randomUUID().toString();
        redis.opsForValue().set(
                "refresh:" + user.username() + ":" + refreshToken,
                user.role(),
                Duration.ofMillis(REFRESH_TTL_MS));
        return Map.of(
                "status", "ok",
                "access_token", accessToken,
                "refresh_token", refreshToken,
                "expires_in", jwtUtil.getAccessTokenExpMs() / 1000);
    }

    private void incrementRate(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(600));
        }
    }

    @GetMapping("/verify")
    public Map<String, Object> verify(Authentication auth) {
        String username = auth.getName();
        String role = auth.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().substring(5))
                .orElse("USER");
        String jti = (String) auth.getDetails();
        return Map.of("status", "ok", "username", username, "role", role, "jti", jti);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(Authentication auth) {
        String jti = (String) auth.getDetails();
        if (jti != null) {
            redis.opsForValue().set("blacklist:" + jti, "1",
                    Duration.ofMillis(jwtUtil.getAccessTokenExpMs()));
        }
        return Map.of("status", "ok", "message", "logged out");
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestParam String refresh_token) {
        int colon = refresh_token.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("invalid refresh token format");
        }
        String username = refresh_token.substring(0, colon);
        String token = refresh_token.substring(colon + 1);
        String key = "refresh:" + username + ":" + token;
        String role = redis.opsForValue().getAndDelete(key);
        if (role == null) {
            throw new BadCredentialsException("invalid or expired refresh token");
        }
        String newAccessToken = jwtUtil.generateAccessToken(username, role);
        String newRefreshToken = UUID.randomUUID().toString();
        redis.opsForValue().set(
                "refresh:" + username + ":" + newRefreshToken,
                role,
                Duration.ofMillis(REFRESH_TTL_MS));
        return Map.of(
                "status", "ok",
                "access_token", newAccessToken,
                "refresh_token", newRefreshToken,
                "expires_in", jwtUtil.getAccessTokenExpMs() / 1000);
    }

    @PostMapping("/unlock")
    public Map<String, Object> unlock(@RequestParam String username) {
        redis.delete("login_fail:" + username);
        return Map.of("status", "ok", "message", "rate limit cleared");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "auth-service");
    }
}
