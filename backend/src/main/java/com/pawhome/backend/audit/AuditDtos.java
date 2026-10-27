package com.pawhome.backend.audit;

import java.time.LocalDateTime;

public final class AuditDtos {

    private AuditDtos() {}

    /**
     * One line of Recent Activity.
     *
     * CARRIES THE SENTENCE, NOT THE INGREDIENTS. `detail` is written at the
     * moment the thing happened and is what the panel prints; the dashboard
     * does not rebuild "X approved Y for Z" from parts. That matters because
     * the parts change -- a pet gets renamed, a shelter gets renamed -- and an
     * audit line that silently updates itself to match today's names is not a
     * record of what happened.
     *
     * action AND entityType ARE STILL HERE, for the icon and the colour. The
     * frontend maps the action to a glyph and falls back to a neutral one for
     * an action it has never heard of, which is what keeps adding a new
     * AuditAction from needing a frontend change.
     */
    public record ActivityRow(
            Long id,
            String action,
            String entityType,
            Long entityId,
            String detail,
            /** The actor's name, or "System". Never null. */
            String actorName,
            LocalDateTime createdAt
    ) {}
}
