package com.pawhome.backend.pet;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.pawhome.backend.health.HealthDtos.PetHealth;
import static com.pawhome.backend.health.HealthDtos.VaccinationRequest;
import static com.pawhome.backend.health.HealthDtos.VaccineTypeRow;
import static com.pawhome.backend.pet.PetDtos.*;

/**
 * The animals belonging to the person signed in.
 *
 * ITS OWN PREFIX, NOT /api/v1/pets/mine. SecurityConfig permits
 * GET /api/v1/pets and /api/v1/pets/* to guests by name, and that second
 * pattern is a single-segment wildcard -- so a route called "mine" hung off
 * that prefix would be a public URL by accident. Everything under /my-pets
 * falls to anyRequest().authenticated() instead, which is what it should be.
 *
 * ADOPTER ONLY. A staff member's own household pet is a real thing and not one
 * this handles: their shelter membership makes every scope question here
 * ambiguous, and the answer is a policy decision rather than something to fall
 * out of a missing role check.
 */
@RestController
@RequestMapping("/api/v1/my-pets")
@PreAuthorize("hasRole('ADOPTER')")
@RequiredArgsConstructor
public class OwnerPetController {

    private final PetService service;
    private final com.pawhome.backend.health.VaccinationService vaccinations;

    /** Adopted and household pets together -- see MyPetRow. */
    @GetMapping
    public List<MyPetRow> mine() {
        return service.myPets();
    }

    /**
     * Register an animal you already own.
     *
     * There is no shelter in the request and no status: a pet registered here
     * is OWNED with no shelter of record, because nobody rehomed it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MyPetRow register(@Valid @RequestBody MyPetRequest request) {
        return service.registerMyPet(request);
    }

    @PutMapping("/{id}")
    public MyPetRow update(@PathVariable Long id, @Valid @RequestBody MyPetRequest request) {
        return service.updateMyPet(id, request);
    }

    /**
     * A real DELETE, unlike everywhere else in this API.
     *
     * An application, an adoption and a boarding stay all end in a status
     * because each is a record of something that happened to somebody. A
     * household pet registered by mistake is not: nothing has happened to it,
     * no shelter has a record of it, and there is nobody it would be unfair
     * to. The service still refuses once any boarding record exists.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        service.removeMyPet(id);
    }

    // -------------------------------------------------------------- photos

    @GetMapping("/{id}")
    public MyPetDetail get(@PathVariable Long id) {
        return service.myPetDetail(id);
    }

    /**
     * Upload one picture.
     *
     * multipart/form-data, so the file never passes through JSON: base64 in a
     * request body inflates every image by a third and has to be held in
     * memory whole at both ends. The same shape as the staff upload, because
     * it is the same mechanism underneath -- only the authorisation differs.
     *
     * `cover` is a request, not a promise: the first photo of a pet becomes
     * the cover whatever this says, since a pet with pictures showing the
     * empty placeholder reads as a failed upload.
     */
    @PostMapping(path = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MyPetDetail addPhoto(@PathVariable Long id,
                                @RequestPart("file") MultipartFile file,
                                @RequestParam(defaultValue = "false") boolean cover) {
        return service.addMyPhoto(id, file, cover);
    }

    @PostMapping("/{id}/photos/{photoId}/cover")
    public MyPetDetail setCover(@PathVariable Long id, @PathVariable Long photoId) {
        return service.setMyCover(id, photoId);
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    public MyPetDetail deletePhoto(@PathVariable Long id, @PathVariable Long photoId) {
        return service.deleteMyPhoto(id, photoId);
    }

    // ------------------------------------------------------- health records
    //
    // AN OWNER MAY RECORD VACCINATIONS FOR A PET FROM HOME, and only for one:
    // the service refuses a shelter's animal, whose history is that shelter's
    // record of its own work. Before this there was nobody at all who could
    // write a household pet's record -- writing needed a shelter, and a
    // household pet has none by definition -- so the one person who knows what
    // the vet gave it had no way to say so.
    //
    // Reading is NOT here: GET /api/v1/pets/{id}/health serves every role, and
    // a second route returning the same rows is a second thing to keep in step.

    @GetMapping("/{id}/vaccinations/types")
    public List<VaccineTypeRow> vaccineTypes(@PathVariable Long id) {
        return vaccinations.typesFor(id);
    }

    @PostMapping("/{id}/vaccinations")
    @ResponseStatus(HttpStatus.CREATED)
    public PetHealth addVaccination(@PathVariable Long id,
                                    @Valid @RequestBody VaccinationRequest request) {
        return vaccinations.add(id, request);
    }

    /** The vet's document. Optional, and PDF is accepted alongside images. */
    @PostMapping(path = "/{id}/vaccinations/{vaccinationId}/certificate",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PetHealth setCertificate(@PathVariable Long id,
                                    @PathVariable Long vaccinationId,
                                    @RequestPart("file") MultipartFile file) {
        return vaccinations.setCertificate(id, vaccinationId, file);
    }

    @DeleteMapping("/{id}/vaccinations/{vaccinationId}")
    public PetHealth deleteVaccination(@PathVariable Long id,
                                       @PathVariable Long vaccinationId) {
        return vaccinations.delete(id, vaccinationId);
    }
}
