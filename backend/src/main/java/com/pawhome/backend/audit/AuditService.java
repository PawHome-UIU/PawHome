package com.pawhome.backend.audit;

import com.pawhome.backend.security.AppUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes the audit trail, and reads it back for the admin dashboard.
 *
 * DEPENDS ON NOTHING BUT ITS OWN TABLE. No ScopeGuard, no other service. That
 * is what lets every service in the application call into this one without
 * Spring finding a cycle -- ApplicationService, BoardingService, PetService and
 * ShelterService all depend on it, so it must depend on none of them.
 *
 * IT READS THE ACTOR FROM THE SECURITY CONTEXT ITSELF rather than taking a user
 * id, and that is the difference between an audit trail and a log. A caller that
 * passes the actor can pass the wrong one -- and the one place it matters most
 * is precisely where somebody might want to. The context is the only answer
 * nobody at the call site can choose.
 *
 * NO @Transactional ON record(). Spring's default propagation joins the
 * caller's transaction, which is deliberate: an audit row for an approval that
 * rolled back would be a record of something that did not happen, and an
 * approval that committed without its audit row is a decision with no author.
 * Either both land or neither does.
 *
 * WHICH MEANS A FAILING AUDIT WRITE ROLLS BACK THE BUSINESS ACTION, and that
 * is the right trade for this table but worth stating out loud. audit_log has
 * no unique keys, no CHECK constraints and one nullable foreign key, so the
 * ways it can fail are the ways the whole transaction was failing anyway.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final JdbcTemplate jdbc;

    /**
     * Record one thing that happened.
     *
     * @param action     past tense, from AuditAction
     * @param entityType what kind of thing it happened to
     * @param entityId   which one -- a SOFT reference, see AuditLog
     * @param detail     one sentence, as the dashboard will print it
     */
    public void record(String action, String entityType, Long entityId, String detail) {
        repository.save(AuditLog.builder()
                .actorId(currentActorId())
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .detail(detail)
                .build());
    }

    /**
     * Who is signed in, or null.
     *
     * NULL RATHER THAN A THROW, because the callers include paths that run with
     * nobody signed in -- MonthlyBillingJob at 03:00, HealthReminderJob -- and
     * an audit trail that refuses to record a system action is worse than one
     * that records it as "System". ScopeGuard.require() is the opposite
     * contract on purpose: it guards user actions and throws when there is no
     * user, which is right there and wrong here.
     *
     * THE instanceof IS LOAD-BEARING. An anonymous request still has an
     * Authentication -- AnonymousAuthenticationToken, whose principal is the
     * string "anonymousUser", not an AppUser. Casting blind would throw on
     * every public page.
     */
    private Long currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof AppUser user ? user.id() : null;
    }

    // ------------------------------------------------------------- reading

    /**
     * The newest entries, with the actor's name resolved.
     *
     * JDBC RATHER THAN THE REPOSITORY, only for this one. The repository
     * returns AuditLog rows carrying an actor ID; the dashboard needs a name,
     * and loading each user separately is one query per row for a panel that
     * shows eight of them. findRecent stays because it is the honest entity
     * read and because it documents the index this query depends on.
     *
     * LEFT JOIN, because actor_id is nullable both ways -- a job ran it, or the
     * person who did has since been deleted (fk_audit_actor is ON DELETE SET
     * NULL). COALESCE rather than letting the caller decide, so "System" is
     * spelled the same everywhere it appears.
     */
    @Transactional(readOnly = true)
    public List<AuditDtos.ActivityRow> recent(int limit) {
        int capped = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
                SELECT  a.id, a.action, a.entity_type, a.entity_id, a.detail,
                        a.created_at,
                        COALESCE(u.full_name, 'System') AS actor_name
                FROM        audit_log a
                LEFT JOIN   users     u ON u.id = a.actor_id
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT ?
                """, (rs, i) -> new AuditDtos.ActivityRow(
                        rs.getLong("id"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getLong("entity_id"),
                        rs.getString("detail"),
                        rs.getString("actor_name"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                capped);
    }
}
