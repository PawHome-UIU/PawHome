package com.pawhome.backend.shelter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShelterSuiteRepository extends JpaRepository<ShelterSuite, Long> {

    /**
     * A shelter's rooms.
     *
     * READS uq_suite_per_shelter (shelter_id, name) END TO END: the filter is
     * the index's first column and the sort is its second, so MySQL walks the
     * index in order and never sorts anything at run time.
     */
    @Query(value = """
           SELECT *
             FROM shelter_suites
            WHERE shelter_id = :shelterId
            ORDER BY name ASC
           """, nativeQuery = true)
    List<ShelterSuite> findByShelterIdOrderByNameAsc(@Param("shelterId") Long shelterId);

    /** Backs the readable message for uq_suite_per_shelter. */
    @Query(value = """
           SELECT *
             FROM shelter_suites
            WHERE shelter_id = :shelterId
              AND UPPER(name) = UPPER(:name)
           """, nativeQuery = true)
    Optional<ShelterSuite> findByShelterIdAndNameIgnoreCase(
            @Param("shelterId") Long shelterId, @Param("name") String name);

    @Query(value = """
           SELECT COUNT(*)
             FROM shelter_suites
            WHERE shelter_id = :shelterId
           """, nativeQuery = true)
    long countByShelterId(@Param("shelterId") Long shelterId);
}
