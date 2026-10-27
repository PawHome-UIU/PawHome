package com.pawhome.backend.boarding;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A note from the shelter while the pet is staying. Maps `boarding_updates`.
 *
 * APPEND-ONLY BY INTENTION. There is no edit and no delete anywhere above
 * this: an update is a statement about what happened at a moment, and the
 * owner reading it a week later has to be able to trust that the note they saw
 * on Tuesday still says what it said. A correction is a further update.
 */
@Entity
@Table(name = "boarding_updates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoardingUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "boarding_id", nullable = false)
    private Long boardingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "update_type", nullable = false)
    private UpdateType updateType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    /** Same storage path shape as pet photos; NULL when there is no picture. */
    @Column(name = "photo_path", length = 255)
    private String photoPath;

    @Column(name = "posted_by", nullable = false)
    private Long postedBy;

    @Column(name = "posted_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime postedAt;
}
