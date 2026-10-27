package com.pawhome.backend.boarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoardingInstructionRepository extends JpaRepository<BoardingInstruction, Long> {

    /** The care instructions for one stay, in the order they were entered. */
    @Query(value = """
           SELECT *
             FROM boarding_instructions
            WHERE boarding_id = :boardingId
            ORDER BY id ASC
           """, nativeQuery = true)
    List<BoardingInstruction> findByBoardingIdOrderByIdAsc(@Param("boardingId") Long boardingId);
}
