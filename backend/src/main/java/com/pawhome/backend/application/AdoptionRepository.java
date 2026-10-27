package com.pawhome.backend.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdoptionRepository extends JpaRepository<Adoption, Long> {

    /** Backs the readable message for uq_adoption_application. */
    @Query(value = """
           SELECT *
             FROM adoptions
            WHERE application_id = :applicationId
           """, nativeQuery = true)
    Optional<Adoption> findByApplicationId(@Param("applicationId") Long applicationId);
}
