package com.pawhome.backend.token;

/** Matches the user_tokens.token_type ENUM in V4. */
public enum TokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,

    /**
     * A staff invitation. The account exists but has never been used, and this
     * link is the only way to give it a password.
     *
     * Its own value rather than a reused PASSWORD_RESET, because redeem()
     * checks the type: sharing one would let a reset link satisfy an invite.
     * It also lives far longer -- see TokenService.
     */
    INVITE
}
