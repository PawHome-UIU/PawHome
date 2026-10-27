package com.pawhome.backend.quiz;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A temperament chip. Maps `traits`.
 *
 * Lives in the quiz package because this is where traits are ASSIGNED -- a
 * personality option maps to one or more of them, and that mapping is the only
 * thing that makes the match score non-zero. Pets carry the same chips through
 * pet_traits, and the score is the overlap.
 *
 * THE SLUG IS THE IDENTITY, not the label. V2 seeds option_traits by joining on
 * slug precisely so that renaming "Friendly" to "Sociable" for the UI does not
 * silently detach every option that mapped to it.
 */
@Entity
@Table(name = "traits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trait {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String label;

    @Column(nullable = false, length = 40)
    private String slug;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
