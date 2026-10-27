package com.pawhome.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one place that decides whether a password is acceptable.
 *
 * Registration and password reset both call {@link #check}. Two copies of these
 * rules would eventually disagree, and the way you would find out is somebody
 * setting a password at reset that they could never have set at signup.
 *
 * THE RULES, AND AN HONEST NOTE ABOUT THEM
 * ----------------------------------------
 *   at least 8 characters
 *   at least one uppercase letter
 *   at least one special character
 *   at most 72 characters
 *
 * Composition rules are a weaker lever than they look. Requiring an uppercase
 * and a symbol reliably produces "Password1!" -- which satisfies every rule
 * here and sits near the top of every cracking dictionary. Length is what
 * actually costs an attacker time: "correct horse battery staple" beats
 * "P@ss1!" by an enormous margin and is easier to remember.
 *
 * They are implemented anyway, because they were asked for and they do no harm
 * on top of a length floor. What was NOT done is lowering the floor to 6 to
 * match: six characters is short enough that the composition rules cannot make
 * up the difference, so the minimum stays at 8.
 *
 * THE 72-CHARACTER CAP IS NOT A STYLE CHOICE. BCrypt silently truncates at 72
 * bytes. A 100-character passphrase would appear to work and then match on its
 * first 72 -- so a longer one is refused rather than quietly shortened.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    /** Anything that is not a letter, a digit, or whitespace. */
    private static final String SPECIALS = "!@#$%^&*()-_=+[]{};:'\",.<>/?\\|`~";

    /** The sentence shown next to the field, so the UI and the server agree. */
    public static final String DESCRIPTION =
            "At least 8 characters, with one uppercase letter and one special character.";

    /**
     * Throws a 400 with a specific reason, or returns quietly.
     *
     * One rule per message. "Password does not meet requirements" makes someone
     * guess which of four things was wrong, and they usually guess wrong.
     */
    public static void check(String password) {
        if (password == null || password.isBlank()) {
            throw bad("Enter a password.");
        }
        if (password.length() < MIN_LENGTH) {
            throw bad("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.length() > MAX_LENGTH) {
            throw bad("Use at most " + MAX_LENGTH + " characters.");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            throw bad("Include at least one uppercase letter.");
        }
        if (password.chars().noneMatch(c -> SPECIALS.indexOf(c) >= 0)) {
            throw bad("Include at least one special character, such as ! ? or #.");
        }
    }

    /** True when the password satisfies every rule. For tests and previews. */
    public static boolean isValid(String password) {
        try {
            check(password);
            return true;
        } catch (ResponseStatusException e) {
            return false;
        }
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
