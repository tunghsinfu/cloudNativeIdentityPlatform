package com.example.user.model;

public record UserProfile(
    Long id,
    String username,
    String displayName,
    String email,
    String avatarUrl,
    String bio
) {}
