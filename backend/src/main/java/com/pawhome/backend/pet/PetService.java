package com.pawhome.backend.pet;

import com.pawhome.backend.audit.AuditAction;
import com.pawhome.backend.audit.AuditService;
import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pawhome.backend.pet.PetDtos.*;

/**
 * Pets, as the shelter that holds them sees them.
 *
 * THIS IS THE FIRST PLACE ScopeGuard's STAFF BRANCH ACTUALLY DOES SOMETHING,
 * and it is worth being precise about why, because the shelter module's
 * comment had to be corrected on this exact point. ShelterService.mine() calls
 * requireShelter with the caller's own shelter id, so the check cannot fail
 * there -- it is documentation, not a gate. Here the id comes off the ROW: a
 * staff member asking for pet 41 gets pet 41's shelter_id compared against
 * their own, and the "belongs to another shelter" branch is genuinely
 * reachable by an ordinary request. Every read and every write in this class
 * goes through it.
 *
 * WHAT THIS SERVICE WILL NOT DO:
 *
 *   NO DELETE. adoption_applications and adoptions reference a pet with ON
 *   DELETE RESTRICT. An adoption that happened last year still has to name the
 *   animal, so a pet leaves the catalogue as UNAVAILABLE and never leaves the
 *   table.
 *
 *   NO ADOPTED OR BOARDING BY HAND. ck_pets_owner_when_placed makes owner_id
 *   mandatory for both, and the owner is decided by an adoption or a boarding
 *   request. Offering the status without the flow would mean asking a staff
 *   member to type a stranger's user id into a dropdown, and getting it wrong
 *   would attach the wrong person to a placement.
 *
 *   NO shelter_id ON THE WAY IN. It is taken from the token, never the body.
 */
@Service
@RequiredArgsConstructor
public class PetService {

    private static final Logger log = LoggerFactory.getLogger(PetService.class);

    private final PetRepository petRepository;
    private final PetPhotoRepository photoRepository;
    private final StorageService storage;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    // ------------------------------------------------------------- reading

    /**
     * The caller's pets.
     *
     * shelterScope() returns null for an ADMIN, meaning "every shelter", and
     * the query is written so that null is a genuine no-op rather than a
     * filter that matches nothing -- the one way that method gets misused.
     */
    @Transactional(readOnly = true)
    public List<PetRow> list() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.query(SELECT_ROW + """
                 WHERE (? IS NULL OR p.shelter_id = ?)
                 ORDER BY p.created_at DESC
                """, ROW_MAPPER, scope, scope);
    }

    @Transactional(readOnly = true)
    public PetDetail get(Long id) {
        PetRow pet = row(id);
        scopeGuard.requireShelter(pet.shelterId());
        return new PetDetail(pet, photos(id));
    }

    @Transactional(readOnly = true)
    public PetCounts counts() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.queryForObject("""
            SELECT  COUNT(*)                      AS total,
                    SUM(status = 'AVAILABLE')     AS available,
                    SUM(status = 'PENDING')       AS pending,
                    SUM(status = 'ADOPTED')       AS adopted,
                    SUM(status = 'BOARDING')      AS boarding,
                    SUM(status = 'UNAVAILABLE')   AS unavailable
            FROM pets WHERE (? IS NULL OR shelter_id = ?)
            """, (rs, i) -> new PetCounts(
                    rs.getLong("total"), rs.getLong("available"), rs.getLong("pending"),
                    rs.getLong("adopted"), rs.getLong("boarding"), rs.getLong("unavailable")),
                scope, scope);
    }

    // -------------------------------------------------------- the catalogue

    /**
     * The public browse list.
     *
     * NO ScopeGuard CALL IN EITHER OF THESE TWO METHODS, and that is deliberate
     * rather than forgotten: the caller may be anonymous, so there is no
     * principal to check. What keeps them safe instead is the projection --
     * PublicPetRow cannot express an owner, a view count or an application
     * count -- and the AVAILABLE filter, which is what makes "every pet in the
     * country" an acceptable answer.
     */
    @Transactional(readOnly = true)
    public List<PublicPetRow> browse(Species species, Long viewerId) {
        String name = species == null ? null : species.name();
        return gate(withMatch(jdbc.query(SELECT_PUBLIC + """
                 WHERE vl.status = 'AVAILABLE'
                   AND (? IS NULL OR vl.species = ?)
                 ORDER BY vl.created_at DESC
                """, PUBLIC_MAPPER, viewerId, name, name), viewerId), viewerId);
    }

    /**
     * A viewer's saved pets, in the order they saved them.
     *
     * Not filtered to AVAILABLE: somebody who saved an animal should be told it
     * has been adopted rather than quietly shown a shorter list.
     */
    @Transactional(readOnly = true)
    public List<PublicPetRow> favorites(Long viewerId) {
        return withMatch(jdbc.query(SELECT_PUBLIC + """
                 JOIN favorites fav ON fav.pet_id = vl.pet_id AND fav.user_id = ?
                 ORDER BY fav.created_at DESC
                """, PUBLIC_MAPPER, viewerId, viewerId), viewerId);
    }

    /**
     * One pet, publicly.
     *
     * An UNAVAILABLE or ADOPTED pet still resolves here rather than 404ing: a
     * link somebody was sent last week should say "already adopted" instead of
     * pretending the animal never existed.
     */
    @Transactional(readOnly = true)
    public PublicPetRow publicPet(Long id, Long viewerId) {
        List<PublicPetRow> found = jdbc.query(SELECT_PUBLIC + " WHERE vl.pet_id = ?",
                PUBLIC_MAPPER, viewerId, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet.");
        }
        return gate(withMatch(found, viewerId), viewerId).get(0);
    }

    /**
     * What a SIGNED-OUT caller is allowed to see.
     *
     * SecurityConfig has demanded this from the beginning, in the comment
     * beside the rule that opens these two routes: "the endpoint MUST return
     * the trimmed projection for an anonymous caller. Withholding traits and
     * shelter location in the React component while the API still ships them
     * is a gate anyone can walk around with devtools." It was never enforced.
     * GuestPetDetailPage told visitors that traits, medical history and
     * shelter location were things an account would get them, while the same
     * fields came back to anyone who asked and the browse grid printed the
     * trait chips on the page.
     *
     * FOUR FIELDS GO, AND THE REST STAY, which is the difference between a
     * gate and a wall. A visitor still sees the photo, the name, the breed,
     * the age, the sex, the size and whether the animal is still available --
     * enough to want an account. What they lose is exactly what the sign-up
     * copy promises: the temperament traits, the write-up, and which shelter
     * has the pet.
     *
     * THE MATCH SCORE NEEDS NO GUARD. It is already null for an anonymous
     * caller, because it is computed from a personality quiz nobody has sat.
     */
    private static List<PublicPetRow> gate(List<PublicPetRow> rows, Long viewerId) {
        if (viewerId != null) return rows;
        // A method reference, not an inline builder chain. The lambda's type
        // then comes from withheld()'s declared return type rather than from
        // inference over the chain -- which is how withMatch is written too,
        // and it keeps the file readable to a compiler that has not run the
        // annotation processor yet.
        return rows.stream().map(PetService::withheld).toList();
    }

    /** One row with the four gated fields emptied. See gate(). */
    private static PublicPetRow withheld(PublicPetRow r) {
        return PublicPetRow.builder()
                .id(r.id()).name(r.name()).species(r.species()).breed(r.breed())
                .ageValue(r.ageValue()).ageUnit(r.ageUnit()).sex(r.sex()).size(r.size())
                .weightKg(r.weightKg()).status(r.status())
                .neutered(r.neutered()).microchipped(r.microchipped())
                .adoptionFee(r.adoptionFee())
                .coverPhotoPath(r.coverPhotoPath()).photoPaths(r.photoPaths())
                // Withheld: an empty list rather than null, so a caller that
                // maps over it does not have to know it was gated.
                .traitLabels(List.of())
                .description(null)
                .shelterName(null).shelterCity(null).shelterState(null)
                .favorite(false)
                .build();
    }

    /**
     * Turn each pet's trait overlap into a percentage.
     *
     * WHAT THE NUMBER MEANS: how many of YOUR traits this pet has. Four traits
     * from your personality quiz and a pet carrying three of them is 75%. The
     * denominator is your trait count, not the pet's, and not the union of
     * both -- because the question a browser is asking is "does this animal
     * suit me", and a calm cat that is also good with children should not
     * score lower for having a quality you never asked about.
     *
     * IT IS COARSE, AND HONESTLY SO. With four traits the only possible
     * answers are 0, 25, 50, 75 and 100. A finer-looking figure would be a
     * decoration over the same four bits of information.
     *
     * NULL, NEVER ZERO, WHEN THERE IS NOTHING TO COMPARE. An anonymous caller
     * and somebody who has not sat the personality quiz both get null, and the
     * card hides the badge -- "0% match" would read as a judgement rather than
     * an absence. The count is fetched ONCE per request, not per pet.
     */
    private List<PublicPetRow> withMatch(List<PublicPetRow> rows, Long viewerId) {
        if (viewerId == null || rows.isEmpty()) return rows;

        // The viewer's own traits, by label rather than by count: the same
        // query answers "how many" and "which ones did this pet miss".
        List<String> mine = jdbc.queryForList(
                "SELECT trait_label FROM v_adopter_traits WHERE user_id = ? ORDER BY trait_label",
                String.class, viewerId);
        if (mine.isEmpty()) return rows;

        List<Long> ids = rows.stream().map(PublicPetRow::id).toList();
        String places = ids.stream().map(x -> "?").collect(Collectors.joining(","));

        // ONE query for the whole page. Per row this would be a round trip per
        // pet, which on a twenty-four card grid is twenty-four.
        java.util.Map<Long, List<String>> matched = new java.util.HashMap<>();
        Object[] args = new Object[ids.size() + 1];
        args[0] = viewerId;
        for (int i = 0; i < ids.size(); i++) args[i + 1] = ids.get(i);
        jdbc.query("""
                SELECT pt.pet_id,
                       GROUP_CONCAT(va.trait_label ORDER BY va.trait_label SEPARATOR '||') AS shared
                  FROM v_adopter_traits va
                  JOIN pet_traits pt ON pt.trait_id = va.trait_id
                 WHERE va.user_id = ? AND pt.pet_id IN (%s)
                 GROUP BY pt.pet_id
                """.formatted(places),
                rs -> { matched.put(rs.getLong("pet_id"), splitLabels(rs.getString("shared"))); },
                args);

        final int denominator = mine.size();
        return rows.stream().map(r -> {
            List<String> hit = matched.getOrDefault(r.id(), List.of());
            List<String> miss = mine.stream().filter(t -> !hit.contains(t)).toList();
            return withMatch(r, hit.size() * 100 / denominator, hit, miss);
        }).toList();
    }

    /** Records are immutable, so a new one with the match filled in. */
    private static PublicPetRow withMatch(PublicPetRow r, int percent,
                                          List<String> hit, List<String> miss) {
        return PublicPetRow.builder()
                .id(r.id()).name(r.name()).species(r.species()).breed(r.breed())
                .ageValue(r.ageValue()).ageUnit(r.ageUnit()).sex(r.sex()).size(r.size())
                .weightKg(r.weightKg()).status(r.status()).description(r.description())
                .neutered(r.neutered()).microchipped(r.microchipped())
                .adoptionFee(r.adoptionFee()).coverPhotoPath(r.coverPhotoPath())
                .photoPaths(r.photoPaths()).traitLabels(r.traitLabels())
                .shelterName(r.shelterName()).shelterCity(r.shelterCity())
                .shelterState(r.shelterState())
                .matchPercent(percent)
                .matchedTraits(hit)
                .missedTraits(miss)
                .favorite(r.favorite())
                .build();
    }

    // ------------------------------------------------------------- writing

    @Transactional
    public PetDetail create(PetRequest r) {
        Long shelterId = scopeGuard.shelterScope();
        if (shelterId == null) {
            // Unreachable today: /api/v1/staff/** is STAFF-only, so the caller
            // always has a shelter. Kept because shelterScope() returns null
            // for an ADMIN and the alternative to this line is a NOT NULL
            // violation on shelter_id -- an error nobody could act on, arriving
            // the first time this service is wired to an admin route.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Admins have no shelter of their own. A pet is added by the "
                    + "staff of the shelter holding it.");
        }

        Pet saved = petRepository.save(Pet.builder()
                .shelterId(shelterId)
                .name(r.name().trim())
                .species(r.species())
                .breed(r.breed().trim())
                .ageValue(r.ageValue())
                .ageUnit(r.ageUnit())
                .sex(r.sex())
                .size(r.size())
                .weightKg(r.weightKg())
                .description(blankToNull(r.description()))
                .neutered(r.neutered())
                .microchipped(r.microchipped())
                .adoptionFee(r.adoptionFee() == null ? java.math.BigDecimal.ZERO : r.adoptionFee())
                // New pets are AVAILABLE with no owner. See the class comment.
                .status(PetStatus.AVAILABLE)
                .ownerId(null)
                .createdBy(scopeGuard.require().id())
                .build());

        writeTraits(saved.getId(), r.traitIds());
        audit.record(AuditAction.PET_ADDED, AuditAction.PET, saved.getId(),
                saved.getName() + " was added to the shelter.");

        log.info("Staff {} added pet {} to shelter {}",
                scopeGuard.require().id(), saved.getId(), shelterId);
        return get(saved.getId());
    }

    @Transactional
    public PetDetail update(Long id, PetRequest r) {
        Pet pet = load(id);
        scopeGuard.requireShelter(pet.getShelterId());

        pet.setName(r.name().trim());
        pet.setSpecies(r.species());
        pet.setBreed(r.breed().trim());
        pet.setAgeValue(r.ageValue());
        pet.setAgeUnit(r.ageUnit());
        pet.setSex(r.sex());
        pet.setSize(r.size());
        pet.setWeightKg(r.weightKg());
        pet.setDescription(blankToNull(r.description()));
        pet.setNeutered(r.neutered());
        pet.setMicrochipped(r.microchipped());
        pet.setAdoptionFee(r.adoptionFee() == null ? java.math.BigDecimal.ZERO : r.adoptionFee());
        // Status, owner and shelter are NOT here. Each has its own path, and
        // letting an edit form carry them is how a pet changes hands by
        // accident.
        petRepository.save(pet);

        writeTraits(id, r.traitIds());
        log.info("Staff {} edited pet {}", scopeGuard.require().id(), id);
        return get(id);
    }

    /**
     * Move a pet between the statuses that need no owner.
     *
     * Idempotent. The two refusals below are the constraint speaking: writing
     * either status without an owner_id fails ck_pets_owner_when_placed, and
     * writing one WITH an owner means deciding who that is -- which is an
     * adoption, not a dropdown.
     */
    @Transactional
    public PetRow setStatus(Long id, PetStatus status) {
        Pet pet = load(id);
        scopeGuard.requireShelter(pet.getShelterId());

        if (status.requiresOwner()) {
            // A switch and not a ternary: there are three of these since V9,
            // and the two-branch version silently told anyone asking for OWNED
            // to go and accept a boarding request.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, switch (status) {
                case ADOPTED -> "A pet becomes adopted by approving an adoption, "
                        + "which is what records the new owner.";
                case BOARDING -> "A pet becomes boarding by accepting a boarding "
                        + "request, which is what records the owner.";
                default -> "A pet becomes owned when the person who owns it "
                        + "registers it themselves. It is not a shelter's to set.";
            });
        }

        if (pet.getStatus().requiresOwner()) {
            // Going the other way is just as wrong: clearing owner_id here
            // would orphan the adoption or the stay that set it.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This pet is " + pet.getStatus().name().toLowerCase()
                    + " and has an owner. That is undone through the "
                    + "adoption or boarding record, not from here.");
        }

        if (pet.getStatus() != status) {
            pet.setStatus(status);
            petRepository.save(pet);
            log.info("Staff {} set pet {} to {}", scopeGuard.require().id(), id, status);
        }
        return row(id);
    }

    // -------------------------------------------------------------- photos

    /**
     * Add a photo, and make it the cover if the pet has none.
     *
     * THE ORDER OF THE TWO WRITES IS FORCED BY uq_pet_cover. The unique key is
     * on (pet_id, cover_flag) where cover_flag is 1 for a cover row and NULL
     * otherwise, so setting a second cover before clearing the first collides.
     * Clear, then set -- never the reverse, and never both in one statement.
     */
    @Transactional
    public PetDetail addPhoto(Long id, MultipartFile file, boolean makeCover) {
        scopeGuard.requireShelter(load(id).getShelterId());
        storePhoto(id, file, makeCover);
        return get(id);
    }

    @Transactional
    public PetDetail setCover(Long id, Long photoId) {
        scopeGuard.requireShelter(load(id).getShelterId());
        promoteCover(id, photoId);
        return get(id);
    }

    /**
     * Remove a photo.
     *
     * The ROW goes first and the FILE second, and never the other way round: a
     * deleted file with a surviving row is a permanent broken image, while a
     * deleted row with a surviving file is some wasted disk nobody sees.
     */
    @Transactional
    public PetDetail deletePhoto(Long id, Long photoId) {
        scopeGuard.requireShelter(load(id).getShelterId());
        removePhoto(id, photoId);
        return get(id);
    }

    /**
     * Remove a pet outright.
     *
     * THIS IS NOT THE OPPOSITE OF WHAT THE CLASS COMMENT SAYS, it is the
     * precise version of it. "A pet is never deleted" is true of a pet with a
     * history: adoption_applications, adoptions and boarding_requests all
     * reference it ON DELETE RESTRICT, so an adoption that happened last year
     * keeps naming the animal and the database enforces that on its own. What
     * that rule was never meant to cover is the pet somebody added twice, or
     * typed the wrong name into, five minutes ago -- and leaving them no way
     * out but UNAVAILABLE means the catalogue slowly fills with corrections
     * nobody can clear.
     *
     * So the check is the constraint's own: no application, no adoption, no
     * boarding request, or this refuses. Everything else that points at a pet
     * cascades because the schema says it belongs to the animal -- photos,
     * traits, favourites, care logs, care tasks, vaccinations.
     *
     * THE ROWS GO BEFORE THE FILES, and the flush is what makes that true. If
     * the delete is going to fail it fails while the files are still there;
     * unlinking first would leave a pet with broken images and no way back.
     */
    @Transactional
    public void delete(Long id) {
        Pet pet = load(id);
        scopeGuard.requireShelter(pet.getShelterId());

        long blocking = row(id).blockingCount();
        if (blocking > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " has " + blocking + " application, adoption or "
                    + "boarding record attached, and those have to keep naming the "
                    + "pet they were about. Take the pet off the catalogue instead.");
        }

        List<String> files = photoRepository.findByPetIdOrderBySortOrderAscIdAsc(id)
                .stream().map(PetPhoto::getFilePath).toList();

        petRepository.delete(pet);
        petRepository.flush();
        files.forEach(storage::delete);

        // RECORDED BEFORE THE ROW IS GONE and deliberately not a foreign
        // key: "pet 47 was removed" has to stay readable after pet 47 no
        // longer exists, which is why AuditLog references entities softly.
        audit.record(AuditAction.PET_REMOVED, AuditAction.PET, id,
                pet.getName() + " was removed from the shelter.");

        log.warn("Staff {} deleted pet {} ({}) from shelter {}",
                scopeGuard.require().id(), id, pet.getName(), pet.getShelterId());
    }

    // -------------------------------------------------- the owner's own pets

    /**
     * Every animal belonging to the caller: adopted from this network, or
     * registered from their own home.
     *
     * ONE LIST, TWO KINDS, and deliberately not two endpoints. To the person
     * signing in they are simply their pets; the difference only matters when
     * they try to edit one, which is what MyPetRow.editable carries.
     */
    @Transactional(readOnly = true)
    public List<MyPetRow> myPets() {
        return jdbc.query(MY_PET_SQL + " WHERE p.owner_id = ? ORDER BY p.name",
                MY_PET_MAPPER, scopeGuard.require().id());
    }

    /**
     * Register an animal you already own.
     *
     * THE THREE COLUMNS NOT TAKEN FROM THE REQUEST ARE THE WHOLE POINT:
     *
     *   shelter_id  NULL   -- no shelter has ever had this animal, so none is
     *                         credited with it, none lists it, and none counts
     *                         it. V9 gives the null that meaning.
     *   status      OWNED  -- not ADOPTED. Nobody adopted it.
     *   owner_id    the caller, which ck_pets_owner_when_placed requires for
     *                         OWNED and which is not the browser's to name.
     *
     * created_by is also the caller. It is the only place in this service
     * where that column is not a staff member, and it is honest: they did
     * create the record.
     */
    @Transactional
    public MyPetRow registerMyPet(MyPetRequest r) {
        Long me = scopeGuard.require().id();

        Pet saved = petRepository.save(Pet.builder()
                .shelterId(null)
                .name(r.name().trim())
                .species(r.species())
                .breed(r.breed().trim())
                .ageValue(r.ageValue())
                .ageUnit(r.ageUnit())
                .sex(r.sex())
                .size(r.size())
                .weightKg(r.weightKg())
                .description(blankToNull(r.description()))
                .neutered(r.neutered())
                .microchipped(r.microchipped())
                // Not for sale, so there is no fee. The column is NOT NULL.
                .adoptionFee(java.math.BigDecimal.ZERO)
                .status(PetStatus.OWNED)
                .ownerId(me)
                .createdBy(me)
                .build());

        log.info("User {} registered their own pet {}", me, saved.getId());
        return myPet(saved.getId());
    }

    @Transactional
    public MyPetRow updateMyPet(Long id, MyPetRequest r) {
        Pet pet = requireMyPetToWrite(id);

        pet.setName(r.name().trim());
        pet.setSpecies(r.species());
        pet.setBreed(r.breed().trim());
        pet.setAgeValue(r.ageValue());
        pet.setAgeUnit(r.ageUnit());
        pet.setSex(r.sex());
        pet.setSize(r.size());
        pet.setWeightKg(r.weightKg());
        pet.setDescription(blankToNull(r.description()));
        pet.setNeutered(r.neutered());
        pet.setMicrochipped(r.microchipped());
        // Status, owner and shelter are NOT here, for the same reason they are
        // absent from the staff edit: an edit form that carries them is how a
        // pet changes hands by accident.
        petRepository.save(pet);
        return myPet(id);
    }

    /**
     * Remove one.
     *
     * REFUSED WHILE ANY BOARDING RECORD EXISTS, which is fk_boarding_pet
     * (ON DELETE RESTRICT) speaking before it has to. A stay last winter still
     * has to name the animal it was about, so the row cannot go -- and a
     * foreign-key violation surfacing as a 500 would tell the owner nothing.
     *
     * A pet in the middle of a stay is refused earlier still, by the status
     * check in requireMyHouseholdPet: it is in somebody's building.
     */
    @Transactional
    public void removeMyPet(Long id) {
        Pet pet = requireMyHouseholdPet(id);

        Long blocking = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boarding_requests WHERE pet_id = ?", Long.class, id);
        if (blocking != null && blocking > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " has " + blocking + " boarding record"
                    + (blocking == 1 ? "" : "s") + " attached, and those have to keep "
                    + "naming the pet they were about.");
        }

        List<String> files = photoRepository.findByPetIdOrderBySortOrderAscIdAsc(id)
                .stream().map(PetPhoto::getFilePath).toList();

        // Rows before files, as in delete(): if this is going to fail it fails
        // while the images are still there.
        petRepository.delete(pet);
        petRepository.flush();
        files.forEach(storage::delete);

        log.info("User {} removed their own pet {} ({})",
                scopeGuard.require().id(), id, pet.getName());
    }

    /**
     * The caller's own pet, in a state where they may write to it.
     *
     * WIDER THAN requireMyHouseholdPet, AND THE SPLIT IS THE WHOLE CHANGE.
     * Every write on /my-pets used to go through the household check, which
     * refused an adopted pet on the argument that its record belongs to the
     * shelter that rehomed it. That argument is right about what the shelter
     * WROTE -- the listing, its photographs -- and wrong about the animal. A
     * dog adopted two years ago has a weight it did not have then, has since
     * been neutered, and its owner has four hundred pictures of it. Under the
     * old rule the shelter had stopped caring and the owner was not allowed,
     * so nobody could say any of it.
     *
     * SO THE PROTECTION MOVED TO WHERE THE SHELTER'S RECORD ACTUALLY IS:
     * deleteMyPhoto refuses a picture somebody else uploaded. The listing's
     * photographs survive; the owner's own do not have to.
     *
     * TWO CHECKS THAT FAIL DIFFERENTLY. requireOwner refuses somebody else's
     * animal. The status test refuses their OWN pet while it is BOARDING: it
     * is in a building somebody else is responsible for, and that shelter's
     * staff are the ones writing about it this week.
     */
    private Pet requireMyPetToWrite(Long id) {
        Pet pet = load(id);
        scopeGuard.requireOwner(pet.getOwnerId());

        if (pet.getStatus() != PetStatus.OWNED && pet.getStatus() != PetStatus.ADOPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " is " + pet.getStatus().name().toLowerCase()
                    + " at the moment, so their details cannot be changed from here.");
        }
        return pet;
    }

    /**
     * The caller's own, and a household one. DELETION ONLY, now.
     *
     * NARROWER THAN requireMyPetToWrite ON PURPOSE. Editing changes what a row
     * says; deleting removes the row an adoption NAMES. fk_adoptions_pet is
     * ON DELETE RESTRICT, so the database would refuse an adopted pet whatever
     * this method decided -- checking here is how that refusal reaches the
     * owner as a sentence rather than as a 500 out of InnoDB.
     *
     * This is exactly what `editable` used to mean, kept intact for the one
     * operation that still needs it.
     */
    private Pet requireMyHouseholdPet(Long id) {
        Pet pet = requireMyPetToWrite(id);

        if (pet.getShelterId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " came from a shelter, and the adoption record "
                    + "names this pet -- so the record cannot be removed. You can "
                    + "still edit their details and add your own photos.");
        }
        return pet;
    }

    private MyPetRow myPet(Long id) {
        return jdbc.queryForObject(MY_PET_SQL + " WHERE p.id = ?", MY_PET_MAPPER, id);
    }

    /** One of the caller's own pets, with its pictures. */
    @Transactional(readOnly = true)
    public MyPetDetail myPetDetail(Long id) {
        Pet pet = load(id);
        scopeGuard.requireOwner(pet.getOwnerId());
        return new MyPetDetail(myPet(id), myPhotos(id));
    }

    /**
     * The caller's view of one pet's pictures: each one, and whether it is
     * theirs to remove.
     *
     * A SECOND MAPPER RATHER THAN A FIELD ON PhotoRow. `deletable` is a fact
     * about the caller's relationship to the photo, not about the photo, so on
     * the public pet page it would be "no" for everyone (noise) and on the
     * staff page it would be "no" wrongly -- staff may remove any picture of
     * their own shelter's animal.
     */
    private List<MyPhotoRow> myPhotos(Long petId) {
        Long me = scopeGuard.require().id();
        return photoRepository.findByPetIdOrderBySortOrderAscIdAsc(petId).stream()
                .map(p -> new MyPhotoRow(p.getId(), p.getFilePath(), p.isCover(),
                        p.getSortOrder(), me.equals(p.getUploadedBy())))
                .toList();
    }

    /**
     * Photos for a pet you own.
     *
     * ADDING AND CHOOSING A COVER ARE OPEN TO BOTH KINDS OF PET, because
     * neither destroys anything: a new picture is additive, and the cover
     * decides which one leads on a page only this owner reads -- an adopted
     * pet is no longer listed, so nothing public moves.
     *
     * DELETING IS THE ONE THAT IS NARROWED, and it is narrowed per PHOTO
     * rather than per pet. You may remove a picture you uploaded. The ones the
     * shelter put on the listing the animal was adopted from are its record of
     * its own work, and they stay. pet_photos.uploaded_by has recorded who
     * uploaded what since V1 -- the column was already carrying this answer
     * and nothing had ever asked it.
     *
     * THAT IS WHY THE FLAG IS ON MyPhotoRow AND NOT ON MyPetRow. "Can this be
     * deleted" now has a different answer for two pictures of the same animal,
     * so a per-pet flag could not express it.
     */
    @Transactional
    public MyPetDetail addMyPhoto(Long id, MultipartFile file, boolean makeCover) {
        requireMyPetToWrite(id);
        storePhoto(id, file, makeCover);
        return myPetDetail(id);
    }

    @Transactional
    public MyPetDetail setMyCover(Long id, Long photoId) {
        requireMyPetToWrite(id);
        promoteCover(id, photoId);
        return myPetDetail(id);
    }

    @Transactional
    public MyPetDetail deleteMyPhoto(Long id, Long photoId) {
        requireMyPetToWrite(id);

        // THE SHELTER'S PICTURES ARE THE SHELTER'S. Checked here rather than
        // trusted from the page: MyPhotoRow.deletable decides which button to
        // draw, and a button that is not drawn is not a rule.
        //
        // requirePhotoOf is the existing helper, so "is this even a photo of
        // this pet" keeps one answer -- without it, owning pet A would be a
        // licence to delete a picture of pet B by passing its id.
        PetPhoto photo = requirePhotoOf(id, photoId);
        if (!photo.getUploadedBy().equals(scopeGuard.require().id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That picture was uploaded by the shelter, so it is part of "
                    + "their record of this adoption. You can delete the photos "
                    + "you added yourself.");
        }

        removePhoto(id, photoId);
        return myPetDetail(id);
    }

    // ------------------------------------------------------------ internals

    // ------------------------------------------------- photo mechanics
    //
    // THE AUTHORISATION IS THE CALLER'S JOB, not these three methods'. A
    // shelter pet is reached through requireShelter and a household pet
    // through requireMyHouseholdPet -- two different questions with two
    // different answers -- but what happens to the file and to uq_pet_cover
    // afterwards is identical, and the moment it is written twice one copy
    // starts forgetting to promote the next cover.
    //
    // Each takes a pet id the caller has ALREADY proved they may touch. Do not
    // call them from a controller.

    private void storePhoto(Long petId, MultipartFile file, boolean makeCover) {
        String stored = storage.store(file, "pets");
        // The first photo is always the cover: a pet with pictures that shows
        // the empty placeholder because nobody ticked a box is a bug nobody
        // reports, they just assume the upload failed.
        boolean first = photoRepository.countByPetId(petId) == 0;
        boolean cover = makeCover || first;

        if (cover) clearCover(petId);

        photoRepository.save(PetPhoto.builder()
                .petId(petId)
                .filePath(stored)
                .cover(cover)
                .sortOrder((short) photoRepository.countByPetId(petId))
                .uploadedBy(scopeGuard.require().id())
                .build());
    }

    private void promoteCover(Long petId, Long photoId) {
        PetPhoto photo = requirePhotoOf(petId, photoId);
        clearCover(petId);
        photo.setCover(true);
        photoRepository.save(photo);
    }

    private void removePhoto(Long petId, Long photoId) {
        PetPhoto photo = requirePhotoOf(petId, photoId);

        boolean wasCover = photo.isCover();
        photoRepository.delete(photo);
        photoRepository.flush();
        storage.delete(photo.getFilePath());

        // Promote the next one, so a pet with photos is never coverless and
        // showing the empty placeholder.
        if (wasCover) {
            photoRepository.findByPetIdOrderBySortOrderAscIdAsc(petId).stream().findFirst()
                    .ifPresent(next -> { next.setCover(true); photoRepository.save(next); });
        }
    }

    /**
     * The photo id is a path variable of its own, so scoping the PET is not
     * enough: without this check anyone who may edit pet 5 could set pet 9's
     * cover by naming pet 9's photo id on pet 5's URL.
     */
    private PetPhoto requirePhotoOf(Long petId, Long photoId) {
        PetPhoto photo = photoRepository.findById(photoId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such photo."));
        if (!photo.getPetId().equals(petId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That photo belongs to a different pet.");
        }
        return photo;
    }

    private void clearCover(Long petId) {
        photoRepository.findByPetIdAndCoverTrue(petId).ifPresent(current -> {
            current.setCover(false);
            photoRepository.save(current);
            // Flushed here rather than at commit: the insert or update that
            // follows sets the new cover, and uq_pet_cover would see both.
            photoRepository.flush();
        });
    }

    /** Replace the pet's trait chips. */
    private void writeTraits(Long petId, List<Long> traitIds) {
        jdbc.update("DELETE FROM pet_traits WHERE pet_id = ?", petId);
        if (traitIds == null || traitIds.isEmpty()) return;

        Set<Long> unique = new HashSet<>(traitIds);
        Set<Long> known = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM traits WHERE is_active = TRUE", Long.class));
        if (!known.containsAll(unique)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "One of those traits no longer exists. Reload and try again.");
        }

        List<Object[]> rows = unique.stream()
                .map(t -> new Object[]{petId, t}).toList();
        // One batch, not a round trip per chip.
        jdbc.batchUpdate("INSERT INTO pet_traits (pet_id, trait_id) VALUES (?, ?)", rows);
    }

    private List<PhotoRow> photos(Long petId) {
        return photoRepository.findByPetIdOrderBySortOrderAscIdAsc(petId).stream()
                .map(p -> new PhotoRow(p.getId(), p.getFilePath(), p.isCover(), p.getSortOrder()))
                .toList();
    }

    private Pet load(Long id) {
        return petRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet."));
    }

    /**
     * One row, unscoped.
     *
     * PRIVATE, and every caller checks the scope itself. A public "get this
     * pet by id" that skipped requireShelter is precisely the hole this module
     * exists to close.
     */
    private PetRow row(Long id) {
        List<PetRow> found = jdbc.query(SELECT_ROW + " WHERE p.id = ?", ROW_MAPPER, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet.");
        }
        return found.get(0);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * The row projection.
     *
     * Correlated subqueries rather than joins: a join to pet_traits duplicates
     * the pet once per chip, and a join to pet_photos once per photo, so a
     * three-chip two-photo pet would arrive six times and the counts would all
     * be wrong. GROUP_CONCAT collapses each to one value in one pass.
     */
    /**
     * The owner's-eye projection.
     *
     * LEFT JOIN, and that single word is the difference from SELECT_ROW. That
     * one joins shelters inline so a household pet can never reach a shelter's
     * screens; this one has to show both kinds in one list, so the join has to
     * survive a null shelter_id.
     */
    private static final String MY_PET_SQL = """
        SELECT  p.id, p.name, p.species, p.breed, p.age_value, p.age_unit,
                p.sex, p.size, p.weight_kg, p.status, p.description,
                p.is_neutered, p.is_microchipped,
                p.shelter_id, s.name AS shelter_name,
                (SELECT ph.file_path FROM pet_photos ph
                  WHERE ph.pet_id = p.id AND ph.is_cover = TRUE
                  LIMIT 1)                                        AS cover_photo_path
        FROM       pets     p
        LEFT JOIN  shelters s ON s.id = p.shelter_id
        """;

    private static final org.springframework.jdbc.core.RowMapper<MyPetRow> MY_PET_MAPPER = (rs, i) -> {
        Long shelterId = rs.getObject("shelter_id") == null ? null : rs.getLong("shelter_id");
        PetStatus status = PetStatus.valueOf(rs.getString("status"));
        return MyPetRow.builder()
                .id(rs.getLong("id"))
                .name(rs.getString("name"))
                .species(Species.valueOf(rs.getString("species")))
                .breed(rs.getString("breed"))
                .ageValue(rs.getShort("age_value"))
                .ageUnit(AgeUnit.valueOf(rs.getString("age_unit")))
                .sex(Sex.valueOf(rs.getString("sex")))
                .size(PetSize.valueOf(rs.getString("size")))
                .weightKg(rs.getBigDecimal("weight_kg"))
                .status(status)
                .description(rs.getString("description"))
                .neutered(rs.getBoolean("is_neutered"))
                .microchipped(rs.getBoolean("is_microchipped"))
                .coverPhotoPath(rs.getString("cover_photo_path"))
                .shelterId(shelterId)
                .shelterName(rs.getString("shelter_name"))
                // EXACTLY THE CONDITIONS THE TWO GUARDS ENFORCE, so the
                // buttons the page draws and the rules the server applies
                // cannot drift apart. They are computed here, once, from the
                // same two values the guards read.
                //
                // editable  -> requireMyPetToWrite: edit details, add photos,
                //              choose the cover. Adopted pets included; a pet
                //              away on a boarding stay excluded.
                .editable(status == PetStatus.OWNED || status == PetStatus.ADOPTED)
                // removable -> requireMyHouseholdPet: delete the record. Never
                //              an adopted pet -- fk_adoptions_pet is RESTRICT
                //              and an adoption names that row.
                .removable(shelterId == null && status == PetStatus.OWNED)
                .build();
    };

    private static final String SELECT_ROW = """
        SELECT  p.id, p.shelter_id, s.name AS shelter_name, p.name, p.species,
                p.breed, p.age_value, p.age_unit, p.sex, p.size, p.weight_kg,
                p.status, p.owner_id, p.description, p.is_neutered,
                p.is_microchipped, p.adoption_fee, p.view_count, p.created_at,
                (SELECT ph.file_path FROM pet_photos ph
                  WHERE ph.pet_id = p.id AND ph.is_cover = TRUE
                  LIMIT 1)                                        AS cover_photo_path,
                (SELECT COUNT(*) FROM pet_photos ph
                  WHERE ph.pet_id = p.id)                         AS photo_count,
                (SELECT GROUP_CONCAT(pt.trait_id ORDER BY pt.trait_id)
                   FROM pet_traits pt WHERE pt.pet_id = p.id)     AS trait_ids,
                (SELECT GROUP_CONCAT(t.label ORDER BY t.label SEPARATOR '||')
                   FROM pet_traits pt JOIN traits t ON t.id = pt.trait_id
                  WHERE pt.pet_id = p.id)                         AS trait_labels,
                (SELECT COUNT(*) FROM adoption_applications a
                  WHERE a.pet_id = p.id)                          AS application_count,
                -- The three tables that reference a pet ON DELETE RESTRICT,
                -- as one number. Every other table pointing at a pet cascades:
                -- photos, traits, favourites, care logs, care tasks and
                -- vaccinations all belong to the animal and go with it.
                ((SELECT COUNT(*) FROM adoption_applications a WHERE a.pet_id = p.id)
               + (SELECT COUNT(*) FROM adoptions ad           WHERE ad.pet_id = p.id)
               + (SELECT COUNT(*) FROM boarding_requests b    WHERE b.pet_id = p.id))
                                                                  AS blocking_count
        FROM       pets     p
        -- INNER, and load-bearing since V9. shelter_id is nullable now and a
        -- NULL means a household pet, so this join is what keeps somebody's own
        -- dog out of a shelter's Manage Pets list. Change it to a LEFT JOIN and
        -- every staff member starts seeing every customer's animals.
        JOIN       shelters s ON s.id = p.shelter_id
        """;

    /**
     * Built by name, not by position.
     *
     * Twenty-five arguments in a row is where a mapper goes wrong silently:
     * shelter_name and name are both Strings, and swapping them compiles.
     */
    private static final org.springframework.jdbc.core.RowMapper<PetRow> ROW_MAPPER =
            (rs, i) -> PetRow.builder()
                    .id(rs.getLong("id"))
                    .shelterId(rs.getLong("shelter_id"))
                    .shelterName(rs.getString("shelter_name"))
                    .name(rs.getString("name"))
                    .species(Species.valueOf(rs.getString("species")))
                    .breed(rs.getString("breed"))
                    .ageValue(rs.getShort("age_value"))
                    .ageUnit(AgeUnit.valueOf(rs.getString("age_unit")))
                    .sex(Sex.valueOf(rs.getString("sex")))
                    .size(PetSize.valueOf(rs.getString("size")))
                    .weightKg(rs.getBigDecimal("weight_kg"))
                    .status(PetStatus.valueOf(rs.getString("status")))
                    .ownerId((Long) rs.getObject("owner_id"))
                    .description(rs.getString("description"))
                    .neutered(rs.getBoolean("is_neutered"))
                    .microchipped(rs.getBoolean("is_microchipped"))
                    .adoptionFee(rs.getBigDecimal("adoption_fee"))
                    .viewCount(rs.getInt("view_count"))
                    .coverPhotoPath(rs.getString("cover_photo_path"))
                    .photoCount(rs.getInt("photo_count"))
                    .traitIds(splitIds(rs.getString("trait_ids")))
                    // '||' as the separator, not a comma: a trait label is free
                    // text and one containing a comma would split into two.
                    .traitLabels(splitLabels(rs.getString("trait_labels")))
                    .applicationCount(rs.getLong("application_count"))
                    .blockingCount(rs.getLong("blocking_count"))
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .build();

    /**
     * The public projection.
     *
     * A DIFFERENT QUERY, not the staff one with columns dropped in Java. If it
     * shared SELECT_ROW then adding a column there -- which is exactly what
     * happened when blockingCount arrived -- would put it one careless mapper
     * change away from the browse page.
     */
    /**
     * The public browse projection -- READ FROM v_pet_listing, not from pets.
     *
     * THIS USED TO BE A HAND-WRITTEN COPY OF THE VIEW. V1 created v_pet_listing
     * saying it was "consumed by FOUR pages"; an audit found nothing had ever
     * selected from it, and this query was one of three places that had rebuilt
     * its derived columns by hand. The vaccinated expression in particular
     * existed twice, with a comment in HealthDtos promising the two agreed --
     * a promise no compiler was checking.
     *
     * V19 WIDENED THE VIEW so this could stop. It now carries the plain columns
     * too (weight, description, neutered, microchipped, the shelter's state)
     * and the gallery, which is why there is no join back to `pets` below.
     *
     * TWO THINGS ARE STILL NOT IN THE VIEW, and both for the same reason: they
     * depend on WHO IS ASKING, and a view has no parameters.
     *
     *   favorite   -- whether THIS viewer saved the pet.
     *   the match  -- filled in afterwards by withMatch(), which needs the
     *                 viewer's quiz traits.
     *
     * THE AVAILABLE FILTER IS STILL THE CALLER'S. The view deliberately carries
     * every listed pet whatever its status, because the staff screens want the
     * pending and unavailable ones too; only the public browse narrows it.
     *
     * SAFE FOR AN ANONYMOUS CALLER BY CONSTRUCTION. The view's own WHERE clause
     * excludes household pets (shelter_id IS NULL), so a private pet cannot
     * appear on the open catalogue even if a caller forgot to filter -- which
     * is a stronger guarantee than the INNER JOIN this query used to rely on,
     * because it now lives in the schema rather than in one query.
     */
    private static final String SELECT_PUBLIC = """
        SELECT  vl.pet_id      AS id,
                vl.pet_name    AS name,
                vl.species, vl.breed, vl.age_value, vl.age_unit, vl.sex,
                vl.size, vl.weight_kg, vl.status, vl.description,
                vl.is_neutered, vl.is_microchipped, vl.adoption_fee,
                vl.shelter_name, vl.shelter_city, vl.shelter_state,
                vl.cover_photo_path,
                vl.photo_paths,
                -- The '||' spelling, not the human-readable ', ' one. The view
                -- carries both because two readers want two things.
                vl.trait_label_list AS trait_labels,
                -- Saved by the viewer. ONE placeholder, and it comes before
                -- every WHERE parameter, so callers pass viewerId first.
                --
                -- `<=>` is MySQL's null-safe equality. It is NOT load-bearing
                -- here: with a NULL viewer id, plain `=` yields NULL for every
                -- row, the subquery returns none, and EXISTS is FALSE -- the
                -- right answer. It stays because it says "a null viewer matches
                -- nobody" in the operator instead of leaving it to three-valued
                -- logic to collapse the right way.
                EXISTS (SELECT 1 FROM favorites f
                         WHERE f.pet_id = vl.pet_id AND f.user_id <=> ?) AS favorite
        FROM       v_pet_listing vl
        """;

    private static final org.springframework.jdbc.core.RowMapper<PublicPetRow> PUBLIC_MAPPER =
            (rs, i) -> PublicPetRow.builder()
                    .id(rs.getLong("id"))
                    .name(rs.getString("name"))
                    .species(Species.valueOf(rs.getString("species")))
                    .breed(rs.getString("breed"))
                    .ageValue(rs.getShort("age_value"))
                    .ageUnit(AgeUnit.valueOf(rs.getString("age_unit")))
                    .sex(Sex.valueOf(rs.getString("sex")))
                    .size(PetSize.valueOf(rs.getString("size")))
                    .weightKg(rs.getBigDecimal("weight_kg"))
                    .status(PetStatus.valueOf(rs.getString("status")))
                    .description(rs.getString("description"))
                    .neutered(rs.getBoolean("is_neutered"))
                    .microchipped(rs.getBoolean("is_microchipped"))
                    .adoptionFee(rs.getBigDecimal("adoption_fee"))
                    .coverPhotoPath(rs.getString("cover_photo_path"))
                    .photoPaths(splitLabels(rs.getString("photo_paths")))
                    .traitLabels(splitLabels(rs.getString("trait_labels")))
                    .shelterName(rs.getString("shelter_name"))
                    .shelterCity(rs.getString("shelter_city"))
                    .shelterState(rs.getString("shelter_state"))
                    .favorite(rs.getBoolean("favorite"))
                    // Filled in by withMatch, which needs two extra queries and
                    // cannot see the viewer from inside a static mapper. Empty
                    // lists rather than null, so a caller never has to guard.
                    .matchPercent(null)
                    .matchedTraits(List.of())
                    .missedTraits(List.of())
                    .build();

    private static List<Long> splitIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> out = new ArrayList<>();
        for (String part : csv.split(",")) out.add(Long.valueOf(part.trim()));
        return out;
    }

    private static List<String> splitLabels(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return List.of(joined.split("\\|\\|"));
    }
}
