package com.pawhome.backend.shelter;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShelterRepository extends JpaRepository<Shelter, Long> {

    /**
     * Used to give a duplicate name a readable message instead of letting
     * uq_shelters_name surface as a 500.
     *
     * The check is not a substitute for the constraint. Two admins adding the
     * same shelter at the same moment both pass this and one of them still
     * loses at the unique key -- which is correct, and why the service catches
     * DataIntegrityViolationException as well.
     *
     * UPPER() ON BOTH SIDES MEANS THE INDEX ON name CANNOT BE USED -- this is
     * a full table scan. Fine over a handful of shelters, and exactly the
     * reason not to write a case-insensitive match against a large table.
     */
    @Query(value = """
           SELECT *
             FROM shelters
            WHERE UPPER(name) = UPPER(:name)
           """, nativeQuery = true)
    Optional<Shelter> findByNameIgnoreCase(@Param("name") String name);

    /** The admin table. */
    @Query(value = """
           SELECT *
             FROM shelters
            ORDER BY name ASC
           """, nativeQuery = true)
    List<Shelter> findAllByOrderByNameAsc();

    /**
     * The shelter row, locked, as a gate on its own headcount.
     *
     * NOTHING HERE IS UPDATED. The lock is not protecting a write to this row
     * -- it is protecting a READ of two OTHER tables from being made twice at
     * the same time. BoardingService.accept has to ask "is this shelter
     * already at capacity" and then, if not, write a stay that changes the
     * answer. Two staff members accepting the last place both read "forty-nine
     * of fifty" without this and the shelter is over its licence by one.
     *
     * THE SHELTER ROW IS THE NATURAL PLACE TO SERIALISE ON because it is the
     * one row every such decision has in common, it exists for certain, and
     * locking it costs nothing anyone else wants -- the reads that draw
     * screens do not take it. The alternative, locking every candidate
     * boarding row, would be a much wider lock for a narrower guarantee.
     *
     * JPQL, NOT NATIVE, and that is forced: @Lock is what appends FOR UPDATE,
     * and Spring Data can only do that to a query it parses. On a nativeQuery
     * the annotation is silently ignored, which would leave this reading like
     * a lock and behaving like a plain SELECT -- the worst of both.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Shelter s WHERE s.id = :id")
    Optional<Shelter> lockById(@Param("id") Long id);

    /** The registration dropdown: only somewhere a new staff member may join. */
    @Query(value = """
           SELECT *
             FROM shelters
            WHERE status = :#{#status.name()}
            ORDER BY name ASC
           """, nativeQuery = true)
    List<Shelter> findByStatusOrderByNameAsc(@Param("status") ShelterStatus status);
}
