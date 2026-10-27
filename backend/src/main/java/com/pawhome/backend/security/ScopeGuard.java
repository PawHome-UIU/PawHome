package com.pawhome.backend.security;

import com.pawhome.backend.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The half of authorisation that roles cannot express.
 *
 * A role answers "may this KIND of user do this KIND of thing". It never
 * answers "may they do it to THIS ROW". Both of these annotations are correct
 * RBAC and both are wide open:
 *
 *   @PreAuthorize("hasRole('STAFF')")     GET /applications/{id}
 *       -> Sarah at Evergreen Paws reads Tail Waggers' applications.
 *
 *   @PreAuthorize("hasRole('ADOPTER')")   GET /applications/{id}
 *       -> any adopter reads any other adopter's application.
 *
 * Nothing in the role fixes either one, because the role is right in both
 * cases. The missing check is about ownership, and it needs the row.
 *
 * WHERE THIS BELONGS. Prefer filtering in the query -- findByIdAndShelterId,
 * or a WHERE on the current user's id -- so a row the caller may not see is
 * never loaded at all. These methods are for the cases where you already hold
 * the row (you needed it to decide) and must assert before returning it.
 *
 * A missed check here does not throw; it silently returns someone else's data.
 * That is why the whole thing is one small class with one obvious name, rather
 * than an `if` copied into thirty services.
 */
@Component
public class ScopeGuard {

    /** The caller, or null when the request is anonymous. */
    public AppUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUser appUser)) return null;
        return appUser;
    }

    /** The caller, or 403 if there isn't one. */
    public AppUser require() {
        AppUser me = current();
        if (me == null) throw new AccessDeniedException("Not authenticated.");
        return me;
    }

    /**
     * Assert the caller may act on data belonging to {@code shelterId}.
     *
     * ADMIN passes for any shelter -- that is what network-wide means.
     * STAFF passes only for their own. ADOPTER never passes: an adopter has no
     * shelter, so there is no shelter whose records are theirs.
     */
    public void requireShelter(Long shelterId) {
        AppUser me = require();

        if (me.is(Role.ADMIN)) return;

        if (me.is(Role.STAFF)) {
            // A null shelterId on the ROW is a bug in the caller, not an open
            // door: refuse rather than let a null match a null.
            if (shelterId == null) {
                throw new AccessDeniedException("This record has no shelter to check against.");
            }
            if (!me.domain().belongsToShelter(shelterId)) {
                throw new AccessDeniedException("That record belongs to another shelter.");
            }
            return;
        }

        throw new AccessDeniedException("Only shelter staff and admins may do that.");
    }

    /**
     * Assert the caller owns this row, by user id.
     *
     * Used for an adopter's own applications, favourites, boarding requests and
     * quiz attempts. ADMIN passes; STAFF does NOT -- a staff member reviewing an
     * application reaches it through requireShelter, not by claiming to be the
     * applicant.
     */
    public void requireOwner(Long ownerId) {
        AppUser me = require();

        if (me.is(Role.ADMIN)) return;

        if (ownerId == null || !ownerId.equals(me.id())) {
            // Deliberately the same message whoever you are. "That belongs to
            // someone else" versus "no such record" tells an attacker which
            // ids exist.
            throw new AccessDeniedException("That record is not yours.");
        }
    }

    /**
     * The shelter a staff caller is scoped to, for use in a query.
     *
     * Returns null for an ADMIN, meaning "no filter -- every shelter". Callers
     * must treat null as network-wide and not as "no shelter", which is the one
     * way to misuse this method:
     *
     *     Long scope = scopeGuard.shelterScope();
     *     return scope == null ? repo.findAll() : repo.findByShelterId(scope);
     */
    public Long shelterScope() {
        AppUser me = require();
        if (me.is(Role.ADMIN)) return null;
        if (me.is(Role.STAFF)) return me.shelterId();
        throw new AccessDeniedException("Only shelter staff and admins may do that.");
    }
}
