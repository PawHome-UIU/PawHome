package com.pawhome.backend.boarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoardingUpdateRepository extends JpaRepository<BoardingUpdate, Long> {

    /**
     * Newest first: the detail page reads as a feed, not as a filing cabinet.
     *
     * id DESC is the tie-break. Two updates posted in the same second would
     * otherwise come back in whatever order the engine felt like, and the feed
     * would reshuffle itself between refreshes.
     */
    @Query(value = """
           SELECT *
             FROM boarding_updates
            WHERE boarding_id = :boardingId
            ORDER BY posted_at DESC, id DESC
           """, nativeQuery = true)
    List<BoardingUpdate> findByBoardingIdOrderByPostedAtDescIdDesc(
            @Param("boardingId") Long boardingId);
}
