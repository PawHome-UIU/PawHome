package com.pawhome.backend.health;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.pawhome.backend.health.HealthDtos.*;

/**
 * Recording shots.
 *
 * NESTED UNDER THE PET, because a vaccination has no meaning apart from the
 * animal it was given to -- and because the pet is what carries the shelter
 * every scope check reads. A flat /vaccinations/{id} would have to look the
 * pet up to answer "may you touch this", which is the same join done later and
 * one more place to forget it.
 *
 * STAFF ONLY, matching the /api/v1/staff/** rule rather than widening it: an
 * admin has no shelter, so requireShelter would pass for every pet in the
 * network on an API written for one.
 */
@RestController
@RequestMapping("/api/v1/staff/pets/{petId}/vaccinations")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffVaccinationController {

    private final VaccinationService service;

    @GetMapping
    public PetHealth list(@PathVariable Long petId) {
        return service.forPet(petId);
    }

    /** The dropdown, already filtered to what this species is given. */
    @GetMapping("/types")
    public List<VaccineTypeRow> types(@PathVariable Long petId) {
        return service.typesFor(petId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PetHealth add(@PathVariable Long petId,
                         @Valid @RequestBody VaccinationRequest request) {
        return service.add(petId, request);
    }

    @PutMapping("/{id}")
    public PetHealth update(@PathVariable Long petId, @PathVariable Long id,
                            @Valid @RequestBody VaccinationRequest request) {
        return service.update(petId, id, request);
    }

    /**
     * A real DELETE, unlike an application or a stay.
     *
     * Those end in a status because each records something that happened to a
     * person. A vaccination row typed against the wrong pet records nothing --
     * it is a mistake, and leaving it visible would make the pet's history
     * wrong in a way no status could explain.
     */
    /**
     * The certificate, uploaded once the record exists.
     *
     * multipart/form-data, and PDF is accepted alongside the image types --
     * a vet hands over a document far more often than a photograph of one.
     * It is served back as a download rather than inline, because a PDF can
     * carry script and /api/v1/files is open to anonymous callers.
     */
    @PostMapping(path = "/{id}/certificate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PetHealth setCertificate(@PathVariable Long petId, @PathVariable Long id,
                                    @RequestPart("file") MultipartFile file) {
        return service.setCertificate(petId, id, file);
    }

    @DeleteMapping("/{id}")
    public PetHealth delete(@PathVariable Long petId, @PathVariable Long id) {
        return service.delete(petId, id);
    }
}
