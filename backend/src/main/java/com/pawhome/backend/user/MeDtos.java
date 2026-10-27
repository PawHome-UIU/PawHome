package com.pawhome.backend.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a person may change about their own account.
 *
 * NO EMAIL, NO ROLE, NO SHELTER, NO APPROVAL STATUS. Each is left out for its
 * own reason and none of them is an oversight:
 *
 *   email    changing it has to re-verify the new address, or an account moves
 *            to an inbox nobody proved they own -- and email_verified would
 *            still say TRUE about the old one. That is the whole verification
 *            flow again, not a field on a settings form.
 *   role     an adopter who could set their own role to ADMIN is the shortest
 *            privilege escalation there is.
 *   shelter  ck_users_staff_shelter and every scope check read it. It moves
 *            through the admin's Move Shelter action, which is audited.
 *   approval an account approving itself is not an approval.
 *
 * NO CITY OR BIO EITHER, and that is a smaller point but the same kind. The
 * mock form collected both; users has neither column, and adding them would
 * mean storing a city that nothing reads -- an adoption application already
 * carries its own copy of the applicant's address, on purpose, because what a
 * reviewer sees has to be what was said when it was submitted.
 */
public final class MeDtos {

    private MeDtos() {}

    public record ProfileRequest(
            @NotBlank(message = "A name is required.")
            @Size(max = 120) String fullName,
            @Size(max = 30) String phone,
            /** Staff only; ignored for anyone else. */
            @Size(max = 80) String jobTitle
    ) {}

    /**
     * Changing a password you know.
     *
     * DIFFERENT FROM THE RESET FLOW, which proves identity with a mailed token
     * because the person cannot sign in. Here they are already signed in, so
     * the proof is the current password -- and it is required for a reason
     * that has nothing to do with the server: a session left open on a shared
     * machine should not be enough to lock the owner out of their own account.
     */
    public record PasswordChangeRequest(
            @NotBlank(message = "Enter your current password.") String currentPassword,
            @NotBlank(message = "Enter a new password.") String newPassword
    ) {}

    /** The four switches from V10. Booleans, so every one is always explicit. */
    public record PreferencesRequest(
            boolean notifyApplications,
            boolean notifyBoarding,
            boolean notifyHealth,
            boolean notifyEmail
    ) {}
}
