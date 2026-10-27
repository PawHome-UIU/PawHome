package com.pawhome.backend.health;

import com.pawhome.backend.pet.Species;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public final class HealthDtos {

    private HealthDtos() {}

    public record VaccineTypeRow(Long id, String name, Species species) {}

    public record VaccinationRow(
            Long id, Long petId, String petName,
            Long vaccineTypeId, String vaccineName,
            LocalDate administeredOn, LocalDate nextDueOn,
            String administeredBy, String batchLot,
            VaccinationStatus status, String certificatePath, String notes,
            String recordedByName,
            /**
             * DERIVED, NEVER STORED. A SCHEDULED shot whose next_due_on has
             * passed. V1 refuses to keep this as a status because a stored
             * "overdue" is a copy of today's date frozen into a row -- wrong
             * the moment the clock passes it, and right again only if
             * something remembers to sweep the table.
             */
            boolean overdue,
            /**
             * Whether THIS caller may remove THIS record.
             *
             * PER ROW, NOT PER PET, and that is the whole reason it exists. An
             * adopted pet's history now has two authors: the shelter that
             * rehomed it, and the owner who has taken it to the vet every year
             * since. Adding is open to both -- a new shot takes nothing away
             * from anyone -- but the owner must not be able to erase what the
             * shelter recorded, so "may this be deleted" has different answers
             * for two rows on one animal and no per-pet flag could carry it.
             *
             * STAFF ARE NOT NARROWED: a shelter's record belongs to the
             * shelter, not to whichever employee was on shift, so any of them
             * may correct any of it. See VaccinationService.requireCanDelete.
             *
             * FALSE FOR A SIGNED-IN STRANGER, along with everything else
             * redact() removes.
             */
            boolean deletable
    ) {}

    /**
     * A pet's health, as the profile shows it.
     *
     * `vaccinated` is THE SAME EXPRESSION v_pet_listing uses -- at least one
     * COMPLETED shot and nothing SCHEDULED past its due date -- so the tick on
     * the pet profile and the tick in the listing cannot disagree. Two places
     * computing the same thing two ways is how one of them ends up lying.
     */
    public record PetHealth(boolean vaccinated, List<VaccinationRow> vaccinations) {}

    public record VaccinationRequest(
            @NotNull(message = "Choose a vaccine.") Long vaccineTypeId,
            @NotNull(message = "When was it given?") LocalDate administeredOn,
            /** ck_vacc_next_due: null, or strictly after administeredOn. */
            LocalDate nextDueOn,
            @NotBlank(message = "Who administered it?")
            @Size(max = 120) String administeredBy,
            @Size(max = 60) String batchLot,
            @NotNull VaccinationStatus status,
            @Size(max = 2000) String notes
    ) {}
}
