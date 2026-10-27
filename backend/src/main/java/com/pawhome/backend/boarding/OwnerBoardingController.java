package com.pawhome.backend.boarding;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.boarding.BoardingDtos.*;

/**
 * Booking a stay, from the customer's side.
 *
 * ADOPTER ONLY, for the reason AdopterApplicationController is: a staff member
 * booking their own pet into their own shelter would then be the person
 * judging the request, and ScopeGuard would let them because the shelter
 * matches. That is a policy decision if anyone ever wants it, not something to
 * fall out of a missing role check.
 *
 * "Adopter" is the role name, not a claim about the animal. These are pets the
 * caller OWNS -- pets.owner_id -- which is what boarding is for.
 */
@RestController
@RequestMapping("/api/v1/boarding")
@PreAuthorize("hasRole('ADOPTER')")
@RequiredArgsConstructor
public class OwnerBoardingController {

    private final BoardingService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardingRow request(@Valid @RequestBody RequestBoarding request) {
        return service.request(request);
    }

    @GetMapping("/mine")
    public List<BoardingRow> mine() {
        return service.mine();
    }

    @GetMapping("/{id}")
    public BoardingDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    /**
     * Pull out.
     *
     * Not a DELETE, for the reason withdrawing an application is not one: the
     * row stays as CANCELLED so the shelter sees a stay that was called off
     * rather than a gap where a booking used to be.
     */
    @PostMapping("/{id}/cancel")
    public BoardingRow cancel(@PathVariable Long id) {
        return service.cancel(id);
    }
}
