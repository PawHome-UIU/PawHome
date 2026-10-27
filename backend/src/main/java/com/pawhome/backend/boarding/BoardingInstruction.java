package com.pawhome.backend.boarding;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of the owner's care notes. Maps `boarding_instructions` from V1.
 *
 * ON DELETE CASCADE from boarding_requests, which is right for this table and
 * would be wrong for most: an instruction has no meaning apart from the stay
 * it describes, so it should not outlive it.
 */
@Entity
@Table(name = "boarding_instructions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoardingInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "boarding_id", nullable = false)
    private Long boardingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstructionCategory category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
}
