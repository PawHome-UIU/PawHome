package com.pawhome.backend.pet;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.pawhome.backend.pet.PetDtos.*;

/**
 * Managing a shelter's animals.
 *
 * STAFF ONLY, AND NOT ALSO ADMIN. SecurityConfig maps /api/v1/staff/** to
 * hasRole("STAFF") and says why in a comment worth re-reading: an admin has no
 * shelter of their own (ck_users_staff_shelter), so shelterScope() returns null
 * for them and every query in PetService would widen to the whole network
 * through an API written for one shelter. Admin access to the same data belongs
 * under /api/v1/admin/**, where the shelter is named explicitly.
 *
 * The annotation therefore matches the URL rule rather than widening it. A
 * @PreAuthorize that disagreed with the matcher would be dead either way -- the
 * matcher runs first -- and reading it would leave the next person believing
 * admins get here.
 *
 * DELETE EXISTS, AND IS NARROW. A pet with an application, an adoption or a
 * boarding request against it cannot be removed -- all three reference it ON
 * DELETE RESTRICT, and the record has to keep naming the animal it was about.
 * PetService refuses those with the reason; UNAVAILABLE is how they leave the
 * catalogue. What delete is for is the pet added twice or typed wrong, which
 * has no history to protect.
 */
@RestController
@RequestMapping("/api/v1/staff/pets")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffPetController {

    private final PetService service;

    @GetMapping
    public List<PetRow> list() {
        return service.list();
    }

    @GetMapping("/counts")
    public PetCounts counts() {
        return service.counts();
    }

    @GetMapping("/{id}")
    public PetDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PetDetail create(@Valid @RequestBody PetRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public PetDetail update(@PathVariable Long id, @Valid @RequestBody PetRequest request) {
        return service.update(id, request);
    }

    /** AVAILABLE, PENDING or UNAVAILABLE. See PetStatus for why not the others. */
    @PostMapping("/{id}/status")
    public PetRow setStatus(@PathVariable Long id,
                            @Valid @RequestBody PetStatusRequest request) {
        return service.setStatus(id, request.status());
    }

    /**
     * Remove a pet that nothing references.
     *
     * 409 with the reason if anything does. See PetService.delete.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    // -------------------------------------------------------------- photos

    /**
     * Upload one image.
     *
     * multipart/form-data, so the file never passes through JSON: base64 in a
     * request body inflates every image by a third and has to be held in
     * memory whole at both ends.
     */
    @PostMapping(path = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PetDetail addPhoto(@PathVariable Long id,
                              @RequestPart("file") MultipartFile file,
                              @RequestParam(defaultValue = "false") boolean cover) {
        return service.addPhoto(id, file, cover);
    }

    @PostMapping("/{id}/photos/{photoId}/cover")
    public PetDetail setCover(@PathVariable Long id, @PathVariable Long photoId) {
        return service.setCover(id, photoId);
    }

    /**
     * The one delete in this module, and it is a photo, not a pet.
     *
     * Nothing references a pet_photos row, so removing one destroys no record
     * of anything that happened.
     */
    @DeleteMapping("/{id}/photos/{photoId}")
    public PetDetail deletePhoto(@PathVariable Long id, @PathVariable Long photoId) {
        return service.deletePhoto(id, photoId);
    }
}
