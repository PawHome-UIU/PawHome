package com.pawhome.backend.user;

/**
 * The three kinds of account, matching the users.role ENUM in V1__init.sql.
 *
 * NAMES MUST MATCH THE DATABASE ENUM EXACTLY. Hibernate stores this with
 * {@code @Enumerated(EnumType.STRING)}, so a rename here without a migration
 * writes a value the column will reject.
 *
 * Spring Security expects authorities prefixed with "ROLE_", which is why
 * {@link #authority()} exists rather than callers concatenating the prefix
 * themselves and getting it wrong in one place out of ten.
 */
public enum Role {

    /** The whole network: shelters, users, quiz questions, reports. */
    ADMIN,

    /**
     * One shelter's pets, applications, care and boarding.
     *
     * Being STAFF is never enough on its own: ck_users_staff_shelter guarantees
     * a shelter_id, and every staff query must filter by it. Role says what
     * kind of thing they may do; the shelter says which rows.
     */
    STAFF,

    /** Their own applications, favourites, boarding and quiz attempts. */
    ADOPTER;

    /** The Spring Security authority string, e.g. "ROLE_STAFF". */
    public String authority() {
        return "ROLE_" + name();
    }
}
