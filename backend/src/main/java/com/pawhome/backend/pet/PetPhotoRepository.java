package com.pawhome.backend.pet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PetPhotoRepository extends JpaRepository<PetPhoto, Long> {

    /** A pet's gallery, in the order the shelter arranged it. */
    @Query(value = """
           SELECT *
             FROM pet_photos
            WHERE pet_id = :petId
            ORDER BY sort_order ASC, id ASC
           """, nativeQuery = true)
    List<PetPhoto> findByPetIdOrderBySortOrderAscIdAsc(@Param("petId") Long petId);

    /**
     * The current cover, which must be cleared before another is set.
     *
     * At most one row can come back, and that is the database's doing rather
     * than this query's: uq_pet_cover (pet_id, cover_flag) makes a second
     * cover for the same pet impossible to insert.
     */
    @Query(value = """
           SELECT *
             FROM pet_photos
            WHERE pet_id = :petId
              AND is_cover = TRUE
           """, nativeQuery = true)
    Optional<PetPhoto> findByPetIdAndCoverTrue(@Param("petId") Long petId);

    @Query(value = """
           SELECT COUNT(*)
             FROM pet_photos
            WHERE pet_id = :petId
           """, nativeQuery = true)
    long countByPetId(@Param("petId") Long petId);
}
