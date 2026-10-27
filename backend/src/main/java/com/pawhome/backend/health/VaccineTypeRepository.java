package com.pawhome.backend.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VaccineTypeRepository extends JpaRepository<VaccineType, Long> {

    /** The vaccines an admin has left switched on, for the record form. */
    @Query(value = """
           SELECT *
             FROM vaccine_types
            WHERE is_active = TRUE
            ORDER BY name ASC
           """, nativeQuery = true)
    List<VaccineType> findByActiveTrueOrderByNameAsc();
}
