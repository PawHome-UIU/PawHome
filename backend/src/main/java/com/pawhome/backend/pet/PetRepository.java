package com.pawhome.backend.pet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PetRepository extends JpaRepository<Pet, Long> {

    /** How many animals a shelter holds. Counted, never stored. */
    @Query(value = """
           SELECT COUNT(*)
             FROM pets
            WHERE shelter_id = :shelterId
           """, nativeQuery = true)
    long countByShelterId(@Param("shelterId") Long shelterId);

    /**
     * Hand a pet over to its adopter. Statement 3 of 4 in
     * ApplicationService.approve.
     *
     * BOTH COLUMNS IN ONE STATEMENT, AND THAT IS NOT A STYLE CHOICE.
     * ck_pets_owner_when_placed says a pet is ADOPTED, BOARDING or OWNED if
     * and only if owner_id is set. A CHECK is evaluated per statement, so
     * setting the status first and the owner second would fail on the first
     * of the two -- there is no instant in between where the row is allowed
     * to exist. One statement, one check, one valid row.
     *
     * The status guard is the same compare-and-set as markApproved: it is
     * what stops a second approval placing an animal that has already gone.
     * PENDING is accepted as well as AVAILABLE because a pet with a decision
     * in progress is still adoptable by the application being approved.
     *
     * updated_at is left alone -- the column is ON UPDATE CURRENT_TIMESTAMP,
     * so MySQL moves it on this statement by itself.
     *
     * @return 1 if the pet was still placeable, 0 if something got there first.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE pets
              SET status   = 'ADOPTED',
                  owner_id = :ownerId
            WHERE id       = :petId
              AND status  IN ('AVAILABLE', 'PENDING')
           """, nativeQuery = true)
    int markAdopted(@Param("petId") Long petId,
                    @Param("ownerId") Long ownerId);
}
