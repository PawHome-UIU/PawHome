package com.pawhome.backend.health;

import com.pawhome.backend.pet.Pet;
import com.pawhome.backend.pet.PetRepository;
import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.pawhome.backend.health.HealthDtos.*;

/**
 * Vaccination records.
 *
 * THIS MODULE WAS THE MISSING SOURCE FOR THREE THINGS THAT ALREADY EXISTED.
 * The table has been in V1 from the beginning, and around it were already
 * built: v_pet_listing's is_vaccinated expression, HealthReminderJob's nightly
 * mail about overdue and due-soon shots, and the pet profile's health section
 * -- which said "ask the shelter about vaccination records" because there was
 * nothing to show. All three were reading, computing over, or apologising for
 * an empty table.
 *
 * OVERDUE IS A QUERY, NOT A STATUS. V1 says so at the column and this service
 * obeys it: `status` is COMPLETED or SCHEDULED, and overdue is
 * `status = 'SCHEDULED' AND next_due_on < CURDATE()` evaluated when somebody
 * asks. A third enum value would be a stored copy of today's date.
 */
@Service
@RequiredArgsConstructor
public class VaccinationService {

    private static final Logger log = LoggerFactory.getLogger(VaccinationService.class);

    private final VaccinationRepository repository;
    private final VaccineTypeRepository typeRepository;
    private final PetRepository petRepository;
    private final StorageService storage;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    /** The dropdown. Species-filtered, since FVRCP is not a dog's booster. */
    @Transactional(readOnly = true)
    public List<VaccineTypeRow> typesFor(Long petId) {
        Pet pet = pet(petId);
        return typeRepository.findByActiveTrueOrderByNameAsc().stream()
                // NULL species means the vaccine applies to everything.
                .filter(t -> t.getSpecies() == null || t.getSpecies() == pet.getSpecies())
                .map(t -> new VaccineTypeRow(t.getId(), t.getName(), t.getSpecies()))
                .toList();
    }

    /**
     * A pet's records.
     *
     * READABLE BY THE SHELTER OR THE OWNER, which is why neither guard alone
     * would do: requireShelter locks the adopter out of their own pet's
     * history, and requireOwner locks the staff out of the animal in their
     * building. A household pet has no shelter at all, so for those the owner
     * check is the only one that can pass.
     */
    @Transactional(readOnly = true)
    public PetHealth forPet(Long petId) {
        Pet pet = pet(petId);
        boolean privileged = isOwnerOrShelter(pet);
        List<VaccinationRow> rows = rows(petId);
        if (!privileged) {
            return new PetHealth(vaccinated(petId),
                    rows.stream().map(VaccinationService::redact).toList());
        }

        // ANSWERED ONCE PER ROW, FROM THE SAME RULE requireCanDelete ENFORCES.
        // Recomputed here rather than shared as a method call per row because
        // the two pet-level facts -- household, or staff rather than owner --
        // are the same for every row and only recorded_by varies.
        Long me = scopeGuard.require().id();
        boolean allMine = pet.getShelterId() == null      // household: all theirs
                || !me.equals(pet.getOwnerId());          // staff: see requireCanDelete
        if (allMine) {
            return new PetHealth(vaccinated(petId),
                    rows.stream().map(r -> withDeletable(r, true)).toList());
        }

        // THE ONLY CALLER THAT GETS A MIXED ANSWER: the owner of an adopted
        // pet, whose own boosters sit in the same list as the shelter's
        // history. One id query rather than a column on VaccinationRow --
        // recorded_by is somebody's user id, and the row is served to the
        // shelter's staff and (redacted) to signed-in strangers, so the
        // narrower thing to ship is the verdict rather than the id.
        Set<Long> mine = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM vaccinations WHERE pet_id = ? AND recorded_by = ?",
                Long.class, petId, me));
        return new PetHealth(vaccinated(petId),
                rows.stream().map(r -> withDeletable(r, mine.contains(r.id()))).toList());
    }

    /**
     * Whether this caller may see the CLINICAL detail as well as the dates.
     *
     * The owner may, and so may the shelter holding the animal. Everybody else
     * signed in gets the redacted view -- see redact().
     */
    private boolean isOwnerOrShelter(Pet pet) {
        var me = scopeGuard.require();
        if (pet.getOwnerId() != null && pet.getOwnerId().equals(me.id())) return true;
        try {
            scopeGuard.requireShelter(pet.getShelterId());
            return true;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            // Not an error: an adopter browsing the catalogue is expected to
            // land here, and gets the shorter answer rather than a refusal.
            return false;
        }
    }

    /**
     * What a signed-in stranger sees.
     *
     * THREE FIELDS GO AND THE REST STAY, and the split is the point. Whether an
     * animal is vaccinated, against what, and when the next dose is due are
     * exactly the questions somebody deciding whether to apply should be able
     * to answer -- withholding them would make the health card useless on the
     * one page where it matters most.
     *
     * What goes is the paperwork: the certificate names a vet and a clinic, the
     * batch number identifies a specific vial, and the notes are whatever the
     * shelter wrote for itself. None of that helps a browser decide, and all of
     * it belongs to the people who recorded it.
     *
     * REDACTED ON THE SERVER, not hidden in the component. The alternative is
     * the gate GuestPetDetailPage spent months claiming to have and did not --
     * fields withheld in React while the API shipped them to anyone with
     * devtools open.
     */
    private static VaccinationRow redact(VaccinationRow r) {
        return new VaccinationRow(
                r.id(), r.petId(), r.petName(),
                r.vaccineTypeId(), r.vaccineName(),
                r.administeredOn(), r.nextDueOn(),
                // administeredBy is the vet's name -- paperwork, not status.
                null, null,
                r.status(), null, null,
                null,
                r.overdue(),
                // A signed-in stranger deletes nothing.
                false);
    }

    /**
     * Every record across the shelter, newest first.
     *
     * SCOPED THROUGH THE PET, which is what has a shelter -- a vaccination has
     * none of its own. shelterScope() returns null for an ADMIN meaning "every
     * shelter", and the query is written so that null is a genuine no-op
     * rather than a filter matching nothing.
     *
     * Household pets have no shelter, so they never appear here: their records
     * belong to their owner, who reads them on the pet's own page.
     */
    @Transactional(readOnly = true)
    public List<VaccinationRow> forShelter() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.query(SELECT_ROW + """
                 WHERE p.shelter_id IS NOT NULL AND (? IS NULL OR p.shelter_id = ?)
                 ORDER BY v.status DESC, v.next_due_on IS NULL, v.next_due_on, v.id DESC
                """, ROW_MAPPER, scope, scope);
    }

    @Transactional
    public PetHealth add(Long petId, VaccinationRequest r) {
        Pet pet = pet(petId);
        requireCanRecord(pet);
        validate(r);

        repository.save(Vaccination.builder()
                .petId(petId)
                .vaccineTypeId(requireType(pet, r.vaccineTypeId()).getId())
                .administeredOn(r.administeredOn())
                .nextDueOn(r.nextDueOn())
                .administeredBy(r.administeredBy().trim())
                .batchLot(blankToNull(r.batchLot()))
                .status(r.status())
                .notes(blankToNull(r.notes()))
                .recordedBy(scopeGuard.require().id())
                .build());

        log.info("Staff {} recorded a vaccination for pet {}", scopeGuard.require().id(), petId);
        return forPet(petId);
    }

    @Transactional
    public PetHealth update(Long petId, Long id, VaccinationRequest r) {
        Pet pet = pet(petId);
        requireCanRecord(pet);
        validate(r);

        Vaccination v = requireRecordOf(petId, id);

        v.setVaccineTypeId(requireType(pet, r.vaccineTypeId()).getId());
        v.setAdministeredOn(r.administeredOn());
        v.setNextDueOn(r.nextDueOn());
        v.setAdministeredBy(r.administeredBy().trim());
        v.setBatchLot(blankToNull(r.batchLot()));
        v.setStatus(r.status());
        v.setNotes(blankToNull(r.notes()));
        repository.save(v);
        return forPet(petId);
    }

    /**
     * Attach the certificate the vet handed over.
     *
     * ITS OWN CALL, AFTER THE RECORD EXISTS. certificate_path hangs off a row,
     * so there is nothing to attach a file to until that row is saved -- and
     * folding the upload into the create would mean one request that can half
     * succeed, leaving a vaccination recorded and a document silently dropped.
     * That is what the old modal did: it collected a PDF and sent nothing.
     *
     * Store, save, then delete the previous one -- the same order as the pet
     * photos and the shelter logo, because it is the only order that fails
     * safely. The worst case is an orphaned file; the alternative is a row
     * pointing at a document that is gone.
     */
    @Transactional
    public PetHealth setCertificate(Long petId, Long id, MultipartFile file) {
        Pet pet = pet(petId);
        requireCanRecord(pet);

        Vaccination v = requireRecordOf(petId, id);
        // REPLACING A CERTIFICATE DESTROYS THE OLD FILE, so it is a delete
        // wearing a different verb and takes the same rule. An adopter
        // overwriting the shelter's scanned certificate with their own would
        // lose the shelter's document for good.
        requireCanDelete(pet, v);

        String previous = v.getCertificatePath();
        v.setCertificatePath(storage.store(file, "certificates"));
        repository.save(v);

        if (previous != null) storage.delete(previous);
        return forPet(petId);
    }

    @Transactional
    public PetHealth delete(Long petId, Long id) {
        Pet pet = pet(petId);
        requireCanRecord(pet);

        Vaccination v = requireRecordOf(petId, id);

        requireCanDelete(pet, v);

        String certificate = v.getCertificatePath();

        // Row first, then the file: if the delete is going to fail it fails
        // while the document is still there.
        repository.delete(v);
        repository.flush();
        if (certificate != null) storage.delete(certificate);

        log.warn("User {} deleted vaccination {} for pet {}",
                scopeGuard.require().id(), id, petId);
        return forPet(petId);
    }

    // --------------------------------------------------------------- shared

    /**
     * The rule the two dates have to satisfy.
     *
     * ck_vacc_next_due says the same thing and would say it as a constraint
     * violation. A SCHEDULED shot with no due date is this service's own rule:
     * the reminder job selects on next_due_on, so one without it is a booking
     * nothing will ever remind anybody about.
     */
    private static void validate(VaccinationRequest r) {
        if (r.nextDueOn() != null && !r.nextDueOn().isAfter(r.administeredOn())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The next dose has to be due after this one was given.");
        }
        if (r.status() == VaccinationStatus.SCHEDULED && r.nextDueOn() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A scheduled dose needs a due date, or nothing will remind anyone.");
        }
    }

    private VaccineType requireType(Pet pet, Long typeId) {
        VaccineType type = typeRepository.findById(typeId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such vaccine."));
        if (type.getSpecies() != null && type.getSpecies() != pet.getSpecies()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    type.getName() + " is not given to a "
                    + pet.getSpecies().name().toLowerCase() + ".");
        }
        return type;
    }

    /**
     * The SAME expression as v_pet_listing.is_vaccinated, deliberately.
     *
     * At least one completed shot, and nothing scheduled that is already
     * overdue. Copying the view's logic is a duplication with a reason: the
     * view cannot be joined here for a household pet, which has no shelter and
     * so does not appear in it at all.
     */
    private boolean vaccinated(Long petId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT (    EXISTS (SELECT 1 FROM vaccinations v
                                     WHERE v.pet_id = ? AND v.status = 'COMPLETED')
                        AND NOT EXISTS (SELECT 1 FROM vaccinations v2
                                         WHERE v2.pet_id = ?
                                           AND v2.status = 'SCHEDULED'
                                           AND v2.next_due_on < CURDATE()))
                """, Boolean.class, petId, petId);
        return Boolean.TRUE.equals(ok);
    }

    private List<VaccinationRow> rows(Long petId) {
        return jdbc.query(SELECT_ROW + " WHERE v.pet_id = ? ORDER BY v.administered_on DESC, v.id DESC",
                ROW_MAPPER, petId);
    }

    private Pet pet(Long petId) {
        return petRepository.findById(petId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet."));
    }

    /**
     * The record, and it must belong to this pet.
     *
     * The vaccination id is a path variable of its own, so scoping the PET is
     * not enough: without this check anyone who may edit pet 5's records could
     * delete pet 9's by naming its id on pet 5's URL.
     */
    /**
     * Who may WRITE a pet's health record.
     *
     * THE SHELTER FOR ITS OWN ANIMALS, AND THE OWNER FOR THEIRS. The second
     * half closed a hole V9 opened without anybody noticing: a pet registered
     * from home has shelter_id NULL, and requireShelter refuses a null shelter
     * outright -- so its vaccination record was unreachable for every single
     * user, including the one person who actually knows what the vet gave it.
     *
     * AN ADOPTED PET IS NOW BOTH, AND THAT IS A CHANGE. It used to be the
     * shelter's alone, on the argument that the animal's history is the record
     * of what that shelter did. The argument is right about the rows the
     * shelter WROTE and produces an absurdity about the rows nobody has
     * written yet: a dog rehomed two years ago gets a rabies booster every
     * year, and the only party permitted to record it was a shelter that has
     * not seen the animal since. Meanwhile the owner -- standing in the vet's
     * office holding the certificate -- was refused.
     *
     * So both may write, and what the shelter recorded is protected where it
     * lives: delete() refuses a row somebody else recorded. Adding is
     * additive and takes nothing away from anyone.
     *
     * This is the same split PetService draws between requireMyPetToWrite and
     * requireMyHouseholdPet, for the same reason and with the same shape --
     * per ROW, not per pet, because "may this be deleted" now has different
     * answers for two shots on one animal.
     */
    private void requireCanRecord(Pet pet) {
        if (pet.getShelterId() == null) {
            scopeGuard.requireOwner(pet.getOwnerId());
            return;
        }
        // An adopted pet has BOTH a shelter of record and an owner. Try the
        // owner first and fall through, rather than asking which role the
        // caller holds: a staff member of that very shelter who also adopted
        // the animal passes either way, and asking "are you the owner" of a
        // pet with no owner is how a NullPointerException gets written.
        if (pet.getOwnerId() != null
                && pet.getOwnerId().equals(scopeGuard.require().id())) {
            return;
        }
        scopeGuard.requireShelter(pet.getShelterId());
    }

    /**
     * Who may REMOVE one row of it. Narrower than requireCanRecord, and only
     * for an adopted pet.
     *
     * THE ASYMMETRY IS THE POINT. requireCanRecord admits both the shelter and
     * the owner for an adopted pet, which is right for ADDING a shot -- that
     * is additive and takes nothing from anyone -- and wrong for deleting one.
     * Without this, letting an adopter record their own booster would also let
     * them erase the vaccination history the shelter built.
     *
     * STAFF ARE NOT NARROWED. A shelter's record is the shelter's, not one
     * employee's: two colleagues share a pet's history, and a typo typed by
     * whoever was on shift must be fixable by whoever notices it. So the
     * per-row rule applies to the OWNER of an adopted pet and to nobody else.
     * A household pet never reaches it at all -- there is no shelter, so every
     * row on it was the owner's.
     *
     * recorded_by has stored the answer since V1; nothing needed migrating.
     */
    private void requireCanDelete(Pet pet, Vaccination v) {
        if (pet.getShelterId() == null) return;                 // household: all theirs
        Long me = scopeGuard.require().id();
        if (!me.equals(pet.getOwnerId())) return;               // staff: see above
        if (!me.equals(v.getRecordedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That record was entered by the shelter, so it is part of "
                    + "their history of this animal. You can remove the records "
                    + "you added yourself.");
        }
    }

    private Vaccination requireRecordOf(Long petId, Long id) {
        Vaccination v = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such vaccination."));
        if (!v.getPetId().equals(petId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That record belongs to a different pet.");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final String SELECT_ROW = """
        SELECT  v.id, v.pet_id, p.name AS pet_name,
                v.vaccine_type_id, vt.name AS vaccine_name,
                v.administered_on, v.next_due_on, v.administered_by, v.batch_lot,
                v.status, v.certificate_path, v.notes,
                r.full_name AS recorded_by_name,
                (v.status = 'SCHEDULED' AND v.next_due_on < CURDATE()) AS overdue
        FROM        vaccinations  v
        JOIN        pets          p  ON p.id  = v.pet_id
        JOIN        vaccine_types vt ON vt.id = v.vaccine_type_id
        JOIN        users         r  ON r.id  = v.recorded_by
        """;

    /**
     * Rebuild one row with `deletable` answered for this caller.
     *
     * A SECOND PASS RATHER THAN A COLUMN, because the answer depends on WHO IS
     * ASKING and ROW_MAPPER is static -- it has a ResultSet and no notion of a
     * caller. Records are immutable, so "answered" means rebuilt, which is the
     * shape redact() already uses for the same reason.
     */
    private static VaccinationRow withDeletable(VaccinationRow r, boolean deletable) {
        return new VaccinationRow(
                r.id(), r.petId(), r.petName(),
                r.vaccineTypeId(), r.vaccineName(),
                r.administeredOn(), r.nextDueOn(),
                r.administeredBy(), r.batchLot(),
                r.status(), r.certificatePath(), r.notes(),
                r.recordedByName(), r.overdue(), deletable);
    }

    private static final RowMapper<VaccinationRow> ROW_MAPPER = (rs, i) -> new VaccinationRow(
            rs.getLong("id"),
            rs.getLong("pet_id"),
            rs.getString("pet_name"),
            rs.getLong("vaccine_type_id"),
            rs.getString("vaccine_name"),
            rs.getDate("administered_on").toLocalDate(),
            rs.getDate("next_due_on") == null ? null : rs.getDate("next_due_on").toLocalDate(),
            rs.getString("administered_by"),
            rs.getString("batch_lot"),
            VaccinationStatus.valueOf(rs.getString("status")),
            rs.getString("certificate_path"),
            rs.getString("notes"),
            rs.getString("recorded_by_name"),
            rs.getBoolean("overdue"),
            // Answered by forPet(), which knows who is asking. False here is
            // the safe default for every path that forgets to ask.
            false);
}
