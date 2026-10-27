package com.pawhome.backend.shelter;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.shelter.ShelterDtos.*;

/**
 * Admin shelter management.
 *
 * Under /api/v1/admin/**, which SecurityConfig already restricts to ADMIN. The
 * class-level @PreAuthorize repeats that on purpose: URL rules live in one
 * file and get reorganised, and a controller that also states its own
 * requirement keeps working if someone moves it. Two locks, one door.
 *
 * NO DELETE MAPPING ANYWHERE IN THIS FILE. A shelter leaves the network by
 * going INACTIVE -- pets, staff accounts and completed adoptions all reference
 * it, and an adoption from last year still has to name the shelter that handed
 * the animal over. The suite endpoint is the same shape for the same reason.
 */
@RestController
@RequestMapping("/api/v1/admin/shelters")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminShelterController {

    private final ShelterService service;

    @GetMapping
    public List<ShelterRow> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ShelterRow get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShelterRow create(@Valid @RequestBody CreateShelterRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ShelterRow update(@PathVariable Long id,
                             @Valid @RequestBody UpdateShelterRequest request) {
        return service.update(id, request);
    }

    /**
     * The logo.
     *
     * multipart/form-data and a separate call, so a shelter can be created
     * without a picture and given one later -- which is how it usually goes,
     * since the person filling in the address rarely has the file to hand.
     */
    @PostMapping(path = "/{id}/logo",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ShelterRow setLogo(@PathVariable Long id,
                              @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return service.setLogo(id, file);
    }

    /** ACTIVE / PENDING / INACTIVE. Its own endpoint, not part of update() --
     *  otherwise a stale edit form can deactivate a shelter by accident. */
    @PostMapping("/{id}/status")
    public ShelterRow setStatus(@PathVariable Long id,
                                @Valid @RequestBody StatusRequest request) {
        return service.setStatus(id, request.status());
    }

    // -------------------------------------------------------------- suites

    @GetMapping("/{id}/suites")
    public List<SuiteRow> suites(@PathVariable Long id) {
        return service.suites(id);
    }

    @PostMapping("/{id}/suites")
    @ResponseStatus(HttpStatus.CREATED)
    public SuiteRow addSuite(@PathVariable Long id,
                             @Valid @RequestBody SuiteRequest request) {
        return service.addSuite(id, request);
    }

    @PutMapping("/{id}/suites/{suiteId}")
    public SuiteRow updateSuite(@PathVariable Long id,
                                @PathVariable Long suiteId,
                                @Valid @RequestBody SuiteRequest request) {
        return service.updateSuite(id, suiteId, request);
    }

    @PostMapping("/{id}/suites/{suiteId}/deactivate")
    public SuiteRow deactivateSuite(@PathVariable Long id, @PathVariable Long suiteId) {
        return service.deactivateSuite(id, suiteId);
    }
}
