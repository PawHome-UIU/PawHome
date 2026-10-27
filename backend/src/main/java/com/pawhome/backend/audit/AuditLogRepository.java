package com.pawhome.backend.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * The newest entries, for the admin dashboard.
     *
     * READS idx_audit_recent (created_at) BACKWARDS, which is what makes this
     * cheap on a table that only grows: InnoDB walks a B-tree in either
     * direction, so "newest first" costs the same as "oldest first" and
     * neither sorts.
     *
     * id DESC AS THE TIE-BREAK, not decoration. created_at is a DATETIME with
     * one-second resolution, and the four writes inside one approve()
     * transaction all land in the same second -- without the second key their
     * order on screen would be whatever the storage engine felt like, and the
     * decision would sometimes appear before the thing that caused it.
     *
     * NO PAGINATION PARAMETER. The dashboard wants a fixed handful; a full
     * audit browser is a different screen with different filters, and giving
     * this one an offset now would be guessing at that screen's shape.
     */
    @Query(value = """
           SELECT *
             FROM audit_log
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
           """, nativeQuery = true)
    List<AuditLog> findRecent(@Param("limit") int limit);
}
