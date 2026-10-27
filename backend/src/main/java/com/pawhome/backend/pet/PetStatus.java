package com.pawhome.backend.pet;

/**
 * Maps `pets.status`.
 *
 * THREE OF THESE SIX ARE THE STAFF'S TO SET; three are not, and the database is
 * what says so:
 *
 *   CONSTRAINT ck_pets_owner_when_placed CHECK (
 *       (status IN ('ADOPTED','BOARDING','OWNED') AND owner_id IS NOT NULL) OR
 *       (status NOT IN ('ADOPTED','BOARDING','OWNED') AND owner_id IS NULL))
 *
 * A pet cannot be ADOPTED, BOARDING or OWNED without an owner, and the owner is
 * decided by an adoption, a boarding request or an owner registering their own
 * animal -- none of which this module builds. PetService therefore refuses
 * those transitions rather than asking a staff member to type somebody's user
 * id into a status dropdown.
 *
 * There is no DELETED. adoption_applications and adoptions reference a pet
 * with ON DELETE RESTRICT: an adoption that happened last year still has to
 * name the animal. UNAVAILABLE is how a pet leaves the catalogue.
 */
public enum PetStatus {

    /** In the shelter, listed, and open to applications. */
    AVAILABLE,

    /** An application is being decided. Still the shelter's, still no owner. */
    PENDING,

    /** Placed by this network. Requires an owner -- set by the adoption flow. */
    ADOPTED,

    /** An owner's pet, staying. Requires an owner -- set by the boarding flow. */
    BOARDING,

    /**
     * A household pet, registered by the person who owns it.
     *
     * NOT THE SAME AS ADOPTED, and the difference is the whole point of the
     * value. ADOPTED means this network rehomed the animal and a shelter is
     * credited with the work; OWNED means it arrived from somebody's home and
     * no shelter has ever had it. Reusing ADOPTED would have saved a migration
     * and labelled a dog raised from a puppy as "adopted" for the rest of its
     * life. These are the rows with shelter_id IS NULL -- see V9.
     */
    OWNED,

    /** Off the catalogue: on hold, in treatment, or not for adoption. */
    UNAVAILABLE;

    /** True when ck_pets_owner_when_placed demands an owner_id. */
    public boolean requiresOwner() {
        return this == ADOPTED || this == BOARDING || this == OWNED;
    }

    /**
     * Where a pet goes when a boarding stay ends.
     *
     * A household pet goes back to OWNED and a rehomed one to ADOPTED, and the
     * shelter of record is what tells them apart: V9 gives shelter_id the
     * meaning "NULL = belongs to a household", so no extra column is needed to
     * remember which kind of animal walked in.
     */
    public static PetStatus restingStatus(Long shelterId) {
        return shelterId == null ? OWNED : ADOPTED;
    }
}
