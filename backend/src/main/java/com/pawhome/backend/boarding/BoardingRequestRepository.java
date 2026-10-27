package com.pawhome.backend.boarding;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BoardingRequestRepository extends JpaRepository<BoardingRequest, Long> {

    /**
     * THE QUERY THAT MAKES DOUBLE-BOOKING IMPOSSIBLE. Read V1's note under
     * CREATE TABLE boarding_requests before changing a character of it.
     *
     * @Lock(PESSIMISTIC_WRITE) is what appends FOR UPDATE, and FOR UPDATE is
     * the entire point. The same SELECT without it is a photograph of the past
     * by the time the caller acts on it: two staff members accepting
     * overlapping stays for one animal in the same second would BOTH read an
     * empty result and BOTH write. With the lock the second transaction blocks
     * on the first's row locks until it commits, and then sees what it wrote.
     *
     * It must run inside a transaction to mean anything -- a lock taken and
     * released before the caller has decided has protected nothing -- so every
     * caller is @Transactional.
     *
     * idx_boarding_overlap (pet_id, start_date, end_date) is what keeps the
     * lock window short. Without it this is a table scan holding locks, which
     * turns a correctness feature into a throughput problem.
     *
     * THE OVERLAP TEST: two closed ranges overlap when each starts on or
     * before the other ends. Not BETWEEN, which would miss a stay that
     * straddles the new dates entirely.
     *
     * NO exclude-self PARAMETER, deliberately. The row being accepted is still
     * REQUESTED and REQUESTED does not hold dates, so it cannot match its own
     * query -- and every caller checks the status before getting here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT b FROM BoardingRequest b
            WHERE b.petId = :petId
              AND b.status IN (com.pawhome.backend.boarding.BoardingStatus.ACCEPTED,
                               com.pawhome.backend.boarding.BoardingStatus.ACTIVE)
              AND b.startDate <= :end
              AND b.endDate   >= :start
            ORDER BY b.startDate
           """)
    List<BoardingRequest> lockOverlappingForPet(@Param("petId") Long petId,
                                                @Param("start") LocalDate start,
                                                @Param("end") LocalDate end);

    /**
     * The same guarantee for the ROOM rather than the animal.
     *
     * Without this, assigning a suite is decoration: two dogs would be booked
     * into Kennel 1 for the same week and nothing anywhere would object,
     * because shelter_suites has no notion of a date. Same lock, same reason.
     *
     * V16 CHANGED WHAT THE CALLER DOES WITH THE ANSWER, NOT THE QUERY. It used
     * to be read as a yes/no -- a non-empty list meant the room was taken.
     * Now that shelter_suites.capacity says how many places a room has, the
     * SIZE of this list is the numerator and capacity is the denominator. The
     * lock is doing exactly the same job either way: without it two staff
     * members both read "nineteen of twenty" and the twenty-first animal is
     * discovered when it is carried in.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT b FROM BoardingRequest b
            WHERE b.suiteId = :suiteId
              AND b.status IN (com.pawhome.backend.boarding.BoardingStatus.ACCEPTED,
                               com.pawhome.backend.boarding.BoardingStatus.ACTIVE)
              AND b.startDate <= :end
              AND b.endDate   >= :start
           """)
    List<BoardingRequest> lockOverlappingForSuite(@Param("suiteId") Long suiteId,
                                                  @Param("start") LocalDate start,
                                                  @Param("end") LocalDate end);

    /**
     * Every room of one shelter, with how many of its places the requested
     * dates would find already spoken for.
     *
     * WAS busySuiteIds, RETURNING A LIST OF IDS, AND THE CHANGE IS THE POINT.
     * "Busy" meant "has anybody in it", which was the right question only
     * while a room held one animal. Since V16 a room has `capacity` places, so
     * the dialog needs a fraction rather than a flag: one dog in a four-place
     * kennel leaves the kennel perfectly offerable, and hiding it would be
     * telling the shelter it has nowhere to put an animal it has room for.
     *
     * TWO SOURCES OF OCCUPANCY, AND THEY ARE NOT SYMMETRICAL.
     *
     *   Overnight stays are counted over the WHOLE requested range, because a
     *   stay holds its room every night of it.
     *
     *   Daycare pets are counted for TODAY ONLY, and only when the requested
     *   range actually covers today. A daycare pet goes home in the afternoon,
     *   so it cannot tell us anything about a room three weeks out -- there is
     *   no attendance row for a day that has not happened, and inventing one
     *   would have a Tuesday cat blocking a room for all of March.
     *
     * SO THIS IS A LOWER BOUND ON A FUTURE DAY, AND EXACT FOR TODAY. That is
     * the most the data supports, and it is the right way round: the figure
     * can only ever understate how full a future day will be, and the
     * check-in path re-counts for real on the day itself.
     *
     * NATIVE, because it counts rows in two other tables against a column in
     * shelter_suites. BoardingRequest.suiteId is a plain Long by the project's
     * convention rather than a @ManyToOne, so there is no association for
     * JPQL to walk.
     *
     * ALIASES ARE camelCase ON PURPOSE. A native query backing an interface
     * projection is matched by ResultSet column LABEL, so `places_used` would
     * not reach getPlacesUsed(); MySQL hands back the alias as written.
     *
     * NO LOCK: this draws a dropdown. A room that fills between the render and
     * the click is caught by lockOverlappingForSuite in accept(), which is
     * where it matters.
     */
    @Query(value = """
           SELECT     su.id AS suiteId,
                      su.capacity AS capacity,
                      ((SELECT COUNT(*)
                          FROM boarding_requests b
                         WHERE b.suite_id = su.id
                           AND b.status IN ('ACCEPTED','ACTIVE')
                           AND b.start_date <= :end
                           AND b.end_date   >= :start)
                     + (SELECT COUNT(*)
                          FROM daycare_attendance a
                         WHERE a.suite_id        = su.id
                           AND a.visit_date      = CURDATE()
                           AND a.checked_out_at IS NULL
                           AND CURDATE() BETWEEN :start AND :end)) AS placesUsed
           FROM       shelter_suites su
           WHERE      su.shelter_id = :shelterId
           """, nativeQuery = true)
    List<SuiteLoad> suiteLoads(@Param("shelterId") Long shelterId,
                               @Param("start") LocalDate start,
                               @Param("end") LocalDate end);

    /**
     * How many kennel places this shelter has spoken for over a period.
     *
     * THE BUILDING, NOT A ROOM. suiteLoads answers "is there space in Cat Loft
     * A"; this answers "is there space in the shelter at all", which is a
     * different refusal with a different remedy -- one says pick another room,
     * the other says this stay cannot happen here.
     *
     * TWO POPULATIONS, AND THE OMISSION IS THE INTERESTING PART.
     *
     *   Residents: pets on this shelter's books that have not been placed. A
     *   pet keeps its shelter_id after adoption, so the status filter is what
     *   makes this a headcount rather than a history.
     *
     *   Boarders holding the requested dates.
     *
     *   DAYCARE PETS ARE DELIBERATELY NOT COUNTED. shelters.capacity counts
     *   KENNEL PLACES -- somewhere for an animal to sleep -- and a daycare pet
     *   goes home at three in the afternoon and needs none. This is the same
     *   distinction V13 drew when it refused to reuse shelters.capacity for
     *   daycare_slot_capacity, and counting them here would quietly undo it.
     *
     * NO FROM CLAUSE. Two scalar subqueries added together is a complete
     * SELECT in MySQL, and inventing a one-row table to hang it off would be
     * noise.
     *
     * UNLOCKED, AND THE CALLER IS WHAT MAKES IT SAFE: accept() takes a
     * pessimistic lock on the shelters row before asking this, so two staff
     * members accepting the last place at the same moment are serialised on
     * that row rather than both reading the same figure.
     */
    @Query(value = """
           SELECT (SELECT COUNT(*)
                     FROM pets p
                    WHERE p.shelter_id = :shelterId
                      AND p.status IN ('AVAILABLE','PENDING'))
                + (SELECT COUNT(*)
                     FROM boarding_requests b
                    WHERE b.shelter_id = :shelterId
                      AND b.status IN ('ACCEPTED','ACTIVE')
                      AND b.start_date <= :end
                      AND b.end_date   >= :start)
           """, nativeQuery = true)
    int countPlacesHeld(@Param("shelterId") Long shelterId,
                        @Param("start") LocalDate start,
                        @Param("end") LocalDate end);

    /**
     * The shape suiteLoads hands back.
     *
     * An interface projection rather than a record, because Spring Data can
     * build one from a native ResultSet without a constructor expression --
     * and a record would need `new com.pawhome...SuiteLoad(...)` inside JPQL,
     * which a native query cannot have.
     */
    interface SuiteLoad {
        Long getSuiteId();
        int getPlacesUsed();
        int getCapacity();
    }
}