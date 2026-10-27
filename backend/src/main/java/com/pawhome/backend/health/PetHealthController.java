package com.pawhome.backend.health;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.pawhome.backend.health.HealthDtos.PetHealth;

/**
 * A pet's health record, read-only.
 *
 * SIGNED IN, AND NOT PUBLIC. SecurityConfig opens GET /api/v1/pets and
 * /api/v1/pets/* to guests, and this route is a segment deeper so it falls to
 * anyRequest().authenticated() -- which is the answer the guest pet page
 * already assumes when it says medical history is one of the things an account
 * gets you. The service then decides between the shelter and the owner.
 *
 * ITS OWN CALL RATHER THAN A FIELD ON THE PET, because a browse grid of
 * twenty-four cards has no use for twenty-four vaccination histories, and
 * v_pet_listing already carries the one-bit answer for a listing.
 */
@RestController
@RequestMapping("/api/v1/pets/{petId}/health")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class PetHealthController {

    private final VaccinationService service;

    @GetMapping
    public PetHealth get(@PathVariable Long petId) {
        return service.forPet(petId);
    }
}
