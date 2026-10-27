package com.pawhome.backend.pet;

import com.pawhome.backend.security.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.pawhome.backend.pet.PetDtos.PublicPetRow;

/**
 * The catalogue, as everyone else sees it.
 *
 * PUBLIC, AND THAT IS WHY IT IS A SEPARATE CONTROLLER WITH A SEPARATE DTO.
 * StaffPetController hands back PetRow, which carries the owner, the view
 * count and how many applications a pet has. Serving that to an anonymous
 * browser and hiding the extra fields in the React component would be a gate
 * anyone opens with devtools. Two controllers, two projections, and no way for
 * a field added to the staff DTO to leak here by accident.
 *
 * NO @PreAuthorize: SecurityConfig permits these two GETs by name, and adding
 * an annotation that says "anyone" would only invite somebody to assume the
 * URL rule says something stricter.
 *
 * THE SAME ROUTE ANSWERS DIFFERENTLY WHEN SIGNED IN. @AuthenticationPrincipal
 * is null for a guest, and the pets come back with no match score and no
 * hearts; for a signed-in caller the same rows carry both. That is why the
 * portal's Shelter Pets page and the guest browse can share one endpoint
 * rather than growing a second, near-identical one that drifts.
 */
@RestController
@RequestMapping("/api/v1/pets")
@RequiredArgsConstructor
public class PublicPetController {

    private final PetService service;

    /**
     * Adoptable pets.
     *
     * AVAILABLE only by default, because that is what a browse page is for:
     * listing an animal that has gone home invites an application that can
     * only be refused. `species` narrows it; everything else the design
     * filters on is done client-side over this list for now.
     */
    @GetMapping
    public List<PublicPetRow> browse(@RequestParam(required = false) Species species,
                                     @AuthenticationPrincipal AppUser me) {
        return service.browse(species, me == null ? null : me.id());
    }

    @GetMapping("/{id}")
    public PublicPetRow get(@PathVariable Long id, @AuthenticationPrincipal AppUser me) {
        return service.publicPet(id, me == null ? null : me.id());
    }
}
