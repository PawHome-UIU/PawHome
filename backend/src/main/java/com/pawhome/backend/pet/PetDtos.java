package com.pawhome.backend.pet;

import jakarta.validation.constraints.*;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PetDtos {

    private PetDtos() {}

    // ------------------------------------------------------------- reading

    /**
     * One card, or one row, on Manage Pets.
     *
     * @Builder BECAUSE TWENTY-FIVE POSITIONAL ARGUMENTS IS A TRAP, and it has
     * already sprung once: adding blockingCount broke a caller silently at the
     * end of a long argument list, and the two Strings either side of it would
     * have compiled fine had they been swapped. Naming each field at the call
     * site makes a reorder impossible to get wrong and a new field impossible
     * to miss. Jackson still serialises through the canonical constructor, so
     * the JSON is unchanged.
     */
    @Builder
    public record PetRow(
            Long id,
            Long shelterId,
            String shelterName,
            String name,
            Species species,
            String breed,
            short ageValue,
            AgeUnit ageUnit,
            Sex sex,
            PetSize size,
            BigDecimal weightKg,
            PetStatus status,
            Long ownerId,
            String description,
            boolean neutered,
            boolean microchipped,
            BigDecimal adoptionFee,
            int viewCount,
            /** "pets/<random>.jpg", or null while a pet has no photos. */
            String coverPhotoPath,
            int photoCount,
            List<Long> traitIds,
            List<String> traitLabels,
            /**
             * Applications and adoptions pointing at this pet.
             *
             * The UI needs it to explain why a pet cannot be taken off the
             * catalogue lightly, and it is the same reason there is no delete.
             */
            long applicationCount,
            /**
             * Records that keep this pet in the table: applications, adoptions
             * and boarding requests, which reference it ON DELETE RESTRICT.
             *
             * Zero means the row can be removed outright. Anything else means
             * the database will refuse, and the UI can say so before somebody
             * spends a confirmation on it.
             */
            long blockingCount,
            LocalDateTime createdAt
    ) {}

    public record PhotoRow(Long id, String filePath, boolean cover, short sortOrder) {}

    /**
     * A pet AS AN ANONYMOUS OR ADOPTER CALLER SEES IT.
     *
     * SecurityConfig opens GET /api/v1/pets to everyone and says why this
     * record has to exist: "the endpoint MUST return the trimmed projection
     * for an anonymous caller. Withholding traits and shelter location in the
     * React component while the API still ships them is a gate anyone can walk
     * around with devtools."
     *
     * So what is missing is the point. No ownerId -- who adopted an animal is
     * nobody else's business. No viewCount, no createdBy, no applicationCount:
     * an applicant who could see that a pet has eleven applications learns
     * something the shelter did not choose to publish. The shelter is named
     * and placed, and not phoned or emailed; ShelterOption already made that
     * call for the same reason.
     */
    @lombok.Builder
    public record PublicPetRow(
            Long id,
            String name,
            Species species,
            String breed,
            short ageValue,
            AgeUnit ageUnit,
            Sex sex,
            PetSize size,
            java.math.BigDecimal weightKg,
            PetStatus status,
            String description,
            boolean neutered,
            boolean microchipped,
            java.math.BigDecimal adoptionFee,
            String coverPhotoPath,
            List<String> photoPaths,
            List<String> traitLabels,
            String shelterName,
            String shelterCity,
            String shelterState,
            /**
             * How many of the viewer's own traits this pet has, as a
             * percentage. NULL for an anonymous caller and NULL for a
             * signed-in one who has never sat the personality quiz -- which is
             * the honest answer, and different from 0%. A card shows the badge
             * only when this is a number.
             */
            Integer matchPercent,
            /**
             * WHY the score is what it is, and the reason both lists are here
             * rather than just the number.
             *
             * A recommendation somebody cannot interrogate is one they cannot
             * trust: "78% match" with no "because" is a number to argue with,
             * while "matched on Calm and Affectionate, did not match Energetic"
             * is a statement they can agree or disagree with. Both lists are
             * empty when matchPercent is null.
             */
            List<String> matchedTraits,
            List<String> missedTraits,
            /** Whether the viewer has saved this pet. False when anonymous. */
            boolean favorite
    ) {}

    public record PetDetail(PetRow pet, List<PhotoRow> photos) {}

    /** Tiles on the staff dashboard header. */
    public record PetCounts(long total, long available, long pending,
                            long adopted, long boarding, long unavailable) {}

    // ------------------------------------------------------------- writing

    /**
     * Everything the Add Pet form collects.
     *
     * NO status AND NO ownerId. A new pet is AVAILABLE; the two statuses that
     * would need an owner are set by the adoption and boarding flows, not by
     * this form -- see PetStatus and ck_pets_owner_when_placed.
     *
     * NO shelterId EITHER. It is the caller's own shelter, taken from the
     * token. Accepting it from the body would mean a staff member could file a
     * pet into someone else's shelter by editing one number.
     */
    public record PetRequest(
            @NotBlank(message = "Give the pet a name.")
            @Size(max = 80) String name,
            @NotNull Species species,
            @NotBlank(message = "Write the breed, or \"Mixed\".")
            @Size(max = 100) String breed,
            @Positive(message = "An age has to be more than zero.")
            @Max(value = 900, message = "That age looks like a typo.")
            short ageValue,
            @NotNull AgeUnit ageUnit,
            @NotNull Sex sex,
            @NotNull PetSize size,
            @Positive(message = "A weight has to be more than zero.")
            @Digits(integer = 3, fraction = 2) BigDecimal weightKg,
            @Size(max = 4000) String description,
            boolean neutered,
            boolean microchipped,
            @PositiveOrZero(message = "A fee cannot be negative.")
            @Digits(integer = 8, fraction = 2) BigDecimal adoptionFee,
            List<Long> traitIds
    ) {}

    /**
     * Changing status by hand.
     *
     * Only the three that need no owner are reachable here; the service
     * refuses ADOPTED and BOARDING with the reason.
     */
    public record PetStatusRequest(@NotNull PetStatus status) {}

    // -------------------------------------------------- an owner's own pets

    /**
     * One animal belonging to the signed-in person.
     *
     * A SEPARATE PROJECTION FROM PetRow, and not because the columns differ
     * much. PetRow's query INNER JOINs shelters, which is exactly what keeps
     * household pets out of a shelter's Manage Pets list -- so it can never
     * return one. This projection LEFT JOINs instead, because the owner's own
     * list has to hold both kinds at once: a dog adopted from Harbor House
     * beside a cat that has been in the family for a decade.
     *
     * shelterName is therefore null for a household pet, and that null is the
     * difference between the two on screen.
     */
    @Builder
    public record MyPetRow(
            Long id,
            String name,
            Species species,
            String breed,
            short ageValue,
            AgeUnit ageUnit,
            Sex sex,
            PetSize size,
            BigDecimal weightKg,
            PetStatus status,
            String description,
            boolean neutered,
            boolean microchipped,
            String coverPhotoPath,
            /** Null for a household pet: no shelter has ever had it. */
            Long shelterId,
            String shelterName,
            /**
             * Whether the owner may change this pet's details and add photos.
             *
             * TRUE FOR AN ADOPTED PET, WHICH IT DID NOT USED TO BE. The old
             * rule was `shelterId == null && status == OWNED`, on the argument
             * that an adopted pet's record belongs to the shelter that rehomed
             * it. That argument holds for what the shelter WROTE and stops
             * there: the animal's weight this year, a new photo, the fact that
             * it has since been neutered are facts about the pet now, and the
             * old rule left nobody able to record them -- the shelter has
             * stopped caring about the animal and the owner was not allowed.
             *
             * What the shelter recorded is protected where it actually lives:
             * MyPhotoRow.deletable refuses to let the owner delete a picture
             * somebody else uploaded, so the listing's photographs survive.
             *
             * STILL FALSE WHILE BOARDING. The animal is in a building somebody
             * else is responsible for, and that shelter's staff are the ones
             * writing about it this week.
             *
             * Sent as a field rather than recomputed in the browser so the
             * page and the server cannot disagree about which buttons exist.
             */
            boolean editable,
            /**
             * Whether the owner may DELETE the record outright.
             *
             * A NARROWER QUESTION THAN `editable`, and the split is the point.
             * Editing changes what a row says; deleting removes the row an
             * adoption NAMES. fk_adoptions_pet is ON DELETE RESTRICT, so for
             * an adopted pet the database would refuse anyway -- this is that
             * refusal arriving as a missing button instead of a 500.
             *
             * So: household pets only, exactly what the old `editable` meant.
             */
            boolean removable
    ) {}

    /**
     * One of the caller's own pets, with its pictures.
     *
     * Separate from MyPetRow for the reason PetDetail is separate from PetRow:
     * the list shows only the cover, and fetching every photo of every pet to
     * draw a grid of one thumbnail each is a query per card.
     */
    public record MyPetDetail(MyPetRow pet, List<MyPhotoRow> photos) {}

    /**
     * One picture, plus whether this owner may remove it.
     *
     * SEPARATE FROM PhotoRow, for the reason MyPetRow is separate from PetRow:
     * `deletable` is a fact about the CALLER's relationship to the photo, not
     * about the photo. Putting it on the shared PhotoRow would mean answering
     * it on the public pet page, where the honest answer is "no" for every
     * viewer and the field is noise -- and on the staff page, where "no" would
     * be a lie, since staff may remove any picture of their own shelter's
     * animal.
     *
     * THE RULE: you may delete a photo you uploaded. pet_photos.uploaded_by
     * has recorded that since V1, so nothing needed migrating -- the column
     * was already carrying the answer and nothing had asked it.
     *
     * The consequence that matters: an adopter can add and remove their own
     * pictures of the pet they now own, and cannot remove the ones the shelter
     * put on the listing it was adopted from.
     */
    public record MyPhotoRow(Long id, String filePath, boolean cover,
                             short sortOrder, boolean deletable) {}

    /**
     * Registering an animal you already own.
     *
     * NO status, NO shelter, NO adoption fee, NO traits. The status is OWNED
     * because that is the only thing a household pet can be; the shelter is
     * null by definition; a fee is meaningless for an animal nobody is
     * offering; and traits feed the adoption matching engine, which has
     * nothing to say about a pet that is not up for adoption.
     */
    public record MyPetRequest(
            @NotBlank(message = "Give your pet a name.")
            @Size(max = 80) String name,
            @NotNull Species species,
            @NotBlank(message = "Write the breed, or \"Mixed\".")
            @Size(max = 100) String breed,
            @Positive(message = "An age has to be more than zero.")
            @Max(value = 900, message = "That age looks like a typo.")
            short ageValue,
            @NotNull AgeUnit ageUnit,
            @NotNull Sex sex,
            @NotNull PetSize size,
            @Positive(message = "A weight has to be more than zero.")
            @Digits(integer = 3, fraction = 2) BigDecimal weightKg,
            @Size(max = 4000) String description,
            boolean neutered,
            boolean microchipped
    ) {}
}
