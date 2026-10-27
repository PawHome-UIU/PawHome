package com.pawhome.backend.pet;

import com.pawhome.backend.security.ScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.pawhome.backend.pet.PetDtos.PublicPetRow;

/**
 * Saved pets.
 *
 * NO @Entity, AND THAT IS THE POINT. `favorites` is (user_id, pet_id) with a
 * composite primary key and nothing else worth naming -- no surrogate id, no
 * status, no history. Mapping it would mean an @IdClass or an @EmbeddedId, a
 * repository and a class, all to express "this pair exists", which two lines
 * of SQL say better. The seam is small enough that if a favourite ever grows a
 * column, giving it an entity then is easy.
 *
 * SAVING IS IDEMPOTENT, by the primary key rather than by a check-then-insert.
 * Two taps on a heart in quick succession race each other, and INSERT IGNORE
 * settles it in the database instead of in a window between two statements.
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final JdbcTemplate jdbc;
    private final PetService petService;
    private final ScopeGuard scopeGuard;

    /** Save. Returns quietly if it was already saved. */
    @Transactional
    public void add(Long petId) {
        Long me = scopeGuard.require().id();

        // fk_favorites_pet would refuse a pet that does not exist, but as a
        // constraint violation. One read turns it into a sentence.
        if (!jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pets WHERE id = ?)",
                Boolean.class, petId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet.");
        }

        jdbc.update("INSERT IGNORE INTO favorites (user_id, pet_id) VALUES (?, ?)", me, petId);
    }

    /**
     * Unsave.
     *
     * Also quiet when it was not saved. A heart is a toggle: telling somebody
     * "that was not in your favourites" after they tapped it to remove it
     * describes the state they wanted anyway.
     */
    @Transactional
    public void remove(Long petId) {
        jdbc.update("DELETE FROM favorites WHERE user_id = ? AND pet_id = ?",
                scopeGuard.require().id(), petId);
    }

    /**
     * The saved list.
     *
     * ADOPTED AND UNAVAILABLE PETS ARE INCLUDED, deliberately. Somebody who
     * saved an animal should be told it has been adopted, not quietly shown a
     * shorter list -- and the card already carries a status badge that says so.
     */
    @Transactional(readOnly = true)
    public List<PublicPetRow> mine() {
        return petService.favorites(scopeGuard.require().id());
    }
}
