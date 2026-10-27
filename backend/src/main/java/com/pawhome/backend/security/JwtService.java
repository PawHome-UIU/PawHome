package com.pawhome.backend.security;

import com.pawhome.backend.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and reads the API's JSON Web Tokens.
 *
 * WHAT IS IN THE TOKEN, AND WHAT IS NOT
 * -------------------------------------
 * The subject is the user id. That is very nearly all of it.
 *
 * Role and shelter_id are deliberately NOT authoritative claims here. This
 * application loads the user row on every request (see JwtAuthFilter), so an
 * admin who deactivates an account or moves a staff member to another shelter
 * changes what that person can do on their very next call. Signing those values
 * into a 24-hour token instead would mean a deactivated user keeps working
 * until it expires, and a transferred staff member keeps reading their old
 * shelter's records -- exactly the leak this system exists to prevent.
 *
 * The role IS included as a non-authoritative convenience claim, so the
 * frontend can pick a landing page without an extra round trip. Nothing on the
 * server ever reads it. If you find yourself trusting {@code claims.get("role")}
 * for an access decision, that is the bug.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /**
     * Must equal the default in application.yaml exactly, or the warning below
     * silently stops firing and a deployment ships with the public dev key.
     */
    private static final String DEV_SECRET =
            "dev-only-pawhome-ed8682d5622bbca5df9e4e398e1c5eb3d8b72a16";

    /** HS384 needs a 384-bit key: 48 bytes. */
    private static final int MIN_SECRET_BYTES = 48;

    private final String secret;
    private final long expirationMs;
    private SecretKey key;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    @PostConstruct
    void init() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);

        // Fail at startup, not at the first login. A key that is too short
        // throws from Keys.hmacShaKeyFor anyway; catching it here says why.
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS384; got "
                            + bytes.length + ". Generate one with: openssl rand -hex 32");
        }

        if (DEV_SECRET.equals(secret)) {
            log.warn("================================================================");
            log.warn(" Using the PUBLIC development JWT secret.");
            log.warn(" Anyone with this repository can mint a valid admin token.");
            log.warn(" Set APP_JWT_SECRET before this is reachable by anyone else.");
            log.warn("================================================================");
        }

        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** Mint a token for a user who has already been authenticated. */
    public String issue(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                // Convenience only -- never read for an access decision.
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Verify a token and return the user id it names.
     *
     * Returns null for anything that is not a valid, unexpired, correctly
     * signed token. A malformed token from a browser is routine (a stale one in
     * localStorage, a truncated copy-paste) -- it is a 401, not an incident, so
     * it is logged at debug and never with the token itself in the message.
     */
    public Long userIdFrom(String token) {
        Verified verified = verify(token);
        return verified == null ? null : verified.userId();
    }

    /**
     * A verified token: who it names, and WHEN IT WAS MINTED.
     *
     * The issue time is the second half of the pair V11 needs -- a token is
     * refused when it predates users.tokens_valid_from, which is how a password
     * change retires every session that was already open. Returned together
     * rather than as a second parser call, because parsing twice is twice the
     * signature verification on every single request.
     */
    public record Verified(Long userId, Instant issuedAt) {}

    public Verified verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Date issued = claims.getIssuedAt();
            return new Verified(Long.valueOf(claims.getSubject()),
                    issued == null ? Instant.EPOCH : issued.toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected a token: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
