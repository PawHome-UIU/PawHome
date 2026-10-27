package com.pawhome.backend.pet;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.pet.PetDtos.PublicPetRow;

/**
 * The heart.
 *
 * AUTHENTICATED, not adopter-only. A favourite is a bookmark against a user
 * id, and there is no reason a staff member browsing the network cannot keep
 * one -- unlike an application, which is a judgement somebody else makes about
 * them. Erring narrow here would be a rule with no argument behind it.
 *
 * PUT AND DELETE RATHER THAN A TOGGLE ENDPOINT. A toggle sends "flip it",
 * which on a flaky connection flips twice and lands where it started; PUT
 * sends the state the person asked for, so a retry is harmless.
 */
@RestController
@RequestMapping("/api/v1/favorites")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService service;

    @GetMapping
    public List<PublicPetRow> mine() {
        return service.mine();
    }

    @PutMapping("/{petId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable Long petId) {
        service.add(petId);
    }

    @DeleteMapping("/{petId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@PathVariable Long petId) {
        service.remove(petId);
    }
}
