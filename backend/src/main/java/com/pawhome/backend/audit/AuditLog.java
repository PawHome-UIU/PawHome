package com.pawhome.backend.audit;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One thing that happened, and who did it. Maps `audit_log` from V1.
 *
 * THE TABLE HAS EXISTED SINCE V1 AND NOTHING EVER WROTE TO IT. Its own comment
 * says it "Powers 'Recent Activity' on the admin dashboard" -- a panel that was
 * rendering a hardcoded array from frontend/src/data/admin.js. So the schema
 * made a claim the code did not keep, which for a table with three indexes and
 * a foreign key is worse than not having it.
 *
 * APPEND-ONLY. There is no update and no delete, and no repository method
 * offers one. An audit trail somebody can edit is not an audit trail, and the
 * absence of those methods is the only thing enforcing that -- MySQL has no way
 * to say "inserts only" on a table.
 *
 * entity_type / entity_id IS A SOFT REFERENCE AND THAT IS DELIBERATE HERE,
 * even though BillingSlip.java:38 calls the same shape an anti-pattern to be
 * avoided *for slips*. The difference is what the two are for: a slip must
 * always resolve to a real stay, so it gets two nullable columns and two real
 * foreign keys. An audit row must survive the thing it describes being deleted
 * -- "staff member removed pet 47" has to stay readable after pet 47 is gone,
 * and a foreign key would either block the delete or cascade the evidence away.
 *
 * actor_id IS NULLABLE for two different reasons that look the same. A job with
 * nobody signed in writes NULL from the start; and fk_audit_actor is ON DELETE
 * SET NULL, so a deleted user's actions become anonymous rather than
 * disappearing. Both render as "System".
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL for a scheduled job, or for a user who has since been deleted. */
    @Column(name = "actor_id")
    private Long actorId;

    /** A verb in the past tense, SCREAMING_SNAKE. See AuditAction. */
    @Column(nullable = false, length = 60)
    private String action;

    /** "PET", "BOARDING", "APPLICATION"... see AuditAction's note on pairs. */
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** One human-readable sentence. What the dashboard actually prints. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
