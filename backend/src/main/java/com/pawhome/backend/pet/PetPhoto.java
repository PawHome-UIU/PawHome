package com.pawhome.backend.pet;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One photo of a pet. Maps `pet_photos`.
 *
 * THE COVER RULE IS THE DATABASE'S, NOT THIS SERVICE'S. V1 enforces "at most
 * one cover photo per pet" with a stored generated column:
 *
 *     cover_flag TINYINT AS (IF(is_cover, 1, NULL)) STORED
 *     UNIQUE KEY uq_pet_cover (pet_id, cover_flag)
 *
 * A MySQL unique index ignores rows where any key part is NULL, so every
 * non-cover photo is exempt while (pet_id, 1) can appear only once. Which
 * means setting a new cover before clearing the old one fails, and PetService
 * clears first for exactly that reason.
 *
 * cover_flag is not mapped: it is generated, and mapping it would have Hibernate
 * try to write a column MySQL computes.
 */
@Entity
@Table(name = "pet_photos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PetPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** What StorageService returned: "pets/<random>.jpg". */
    @Column(name = "file_path", nullable = false, length = 255)
    private String filePath;

    @Column(name = "is_cover", nullable = false)
    @Builder.Default
    private boolean cover = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private short sortOrder = 0;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime uploadedAt;
}
