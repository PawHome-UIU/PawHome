package com.pawhome.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TraitRepository extends JpaRepository<Trait, Long> {

    /** The trait chips, for the Add Pet form and the question editor. */
    @Query(value = """
           SELECT *
             FROM traits
            WHERE is_active = TRUE
            ORDER BY label ASC
           """, nativeQuery = true)
    List<Trait> findByActiveTrueOrderByLabelAsc();
}
