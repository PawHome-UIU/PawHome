package com.pawhome.backend.token;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A single-use link, for verifying an email or resetting a password.
 *
 * `tokenHash` holds a SHA-256 of the value that went out in the email. The raw
 * token is never persisted -- see the V4 migration for why.
 */
@Entity
@Table(name = "user_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false)
    private TokenType tokenType;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Null until redeemed. Non-null means the link has already been used. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Usable exactly once, and only before it expires. */
    public boolean isRedeemable() {
        return usedAt == null && LocalDateTime.now(java.time.ZoneOffset.UTC).isBefore(expiresAt);
    }
}
