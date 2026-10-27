package com.pawhome.backend.token;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues and redeems the single-use links.
 *
 * THE RAW TOKEN EXISTS EXACTLY ONCE. issue() returns it to the caller, who puts
 * it in an email and forgets it. Only its SHA-256 reaches the database, so a
 * dump of user_tokens is a list of useless hashes rather than a set of live
 * account-takeover links.
 *
 * 256 bits from SecureRandom. Not Random, and not UUID.randomUUID(): the first
 * is seeded predictably enough to reconstruct, and the second spends 6 of its
 * 128 bits on version and variant markers. Neither difference matters until the
 * one time it does, and the correct call costs the same.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;   // 256 bits

    private final UserTokenRepository repository;

    @Value("${app.tokens.verification-hours:48}")
    private long verificationHours;

    /**
     * Reset links live for one hour, not 48.
     *
     * A password reset hands over the account outright, so the window in which
     * a leaked email is dangerous should be as short as someone can reasonably
     * act in. Verification only confirms an address, so it can afford to
     * survive a night's sleep.
     */
    @Value("${app.tokens.reset-minutes:60}")
    private long resetMinutes;

    /**
     * Invitations live for a week.
     *
     * Not a relaxed version of the reset window -- a different situation. A
     * reset is somebody acting right now, so an hour is generous. An invite
     * arrives unannounced, and the person it is for may be on leave, off shift,
     * or simply not checking that inbox. A 48-hour invite mostly produces an
     * admin re-sending it, which is worse: more live links, not fewer.
     *
     * It is a longer exposure and that is the trade. It buys the account
     * nothing on its own -- the link only ever sets a password on an account an
     * admin has already vouched for.
     */
    @Value("${app.tokens.invite-days:7}")
    private long inviteDays;

    /** Mints a token, stores its hash, and returns the raw value for emailing. */
    @Transactional
    public String issue(Long userId, TokenType type) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Any previous link of this kind stops working now. Asking for a second
        // reset email must kill the first, or an old message sitting in a
        // compromised inbox stays a working key.
        repository.invalidateOutstanding(userId, type, now);

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe and unpadded: this goes straight into a query string, and
        // '+', '/' and '=' all need escaping there. One un-escaped token in one
        // mail client is a link that silently does not work.
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        LocalDateTime expiry = switch (type) {
            case PASSWORD_RESET -> now.plusMinutes(resetMinutes);
            case INVITE         -> now.plusDays(inviteDays);
            case EMAIL_VERIFICATION -> now.plusHours(verificationHours);
        };

        repository.save(UserToken.builder()
                .userId(userId)
                .tokenHash(sha256(raw))
                .tokenType(type)
                .expiresAt(expiry)
                .build());

        return raw;
    }

    /**
     * Redeem a token, returning the user id it belongs to.
     *
     * Marks it used in the same transaction, so a link clicked twice -- or
     * clicked twice concurrently, which mail-scanning proxies genuinely do --
     * succeeds at most once. The unique index on token_hash plus this update is
     * what makes that safe.
     */
    @Transactional
    public Optional<Long> redeem(String rawToken, TokenType expectedType) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();

        Optional<UserToken> found = repository.findByTokenHash(sha256(rawToken));
        if (found.isEmpty()) return Optional.empty();

        UserToken token = found.get();
        if (token.getTokenType() != expectedType) return Optional.empty();
        if (!token.isRedeemable()) return Optional.empty();

        token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
        repository.save(token);
        return Optional.of(token.getUserId());
    }

    /**
     * Whether a token exists but is spent or stale.
     *
     * Lets the UI say "that link has expired, here is a new one" instead of the
     * flat "invalid link" a stranger gets -- useful, and it discloses nothing,
     * because you already had to hold the token to ask.
     */
    @Transactional(readOnly = true)
    public boolean wasIssuedButUnusable(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return false;
        return repository.findByTokenHash(sha256(rawToken))
                .filter(t -> !t.isRedeemable())
                .isPresent();
    }

    @Transactional
    public int purgeExpired() {
        return repository.deleteExpiredBefore(LocalDateTime.now(ZoneOffset.UTC));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM. If it is missing, something is
            // deeply wrong and carrying on would be worse.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
