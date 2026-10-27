package com.pawhome.backend.user;

/**
 * Whether an account has been let in, matching users.approval_status.
 *
 * This is NOT the same axis as is_active, and conflating them is the mistake
 * this enum exists to prevent:
 *
 *   approval_status  has this account ever been allowed in?     (a decision)
 *   is_active        is it allowed in right now?                (a switch)
 *
 * A staff sign-up starts PENDING and cannot act until an admin approves it.
 * A long-standing member who leaves is APPROVED but is_active = false. The
 * migration's own comment puts it plainly: a login needs BOTH.
 */
public enum ApprovalStatus {

    /** Signed up, waiting on an admin. Authenticates, but may do nothing. */
    PENDING,

    /** Let in. Adopters get this on creation; staff get it from an admin. */
    APPROVED,

    /** Turned down. Kept rather than deleted so the same person cannot
     *  quietly re-apply and appear as a fresh PENDING row. */
    REJECTED
}
