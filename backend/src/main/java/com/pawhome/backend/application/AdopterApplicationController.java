package com.pawhome.backend.application;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.application.ApplicationDtos.*;

/**
 * Applying to adopt, from the applicant's side.
 *
 * ADOPTER ONLY. Staff and admins are refused not out of tidiness but because
 * an application is judged by the shelter holding the pet: a staff member
 * applying for their own shelter's animal would be reviewing their own
 * application, and ScopeGuard would let them, because the pet is in their
 * shelter. If shelters ever need to adopt from each other, that is a decision
 * with a policy behind it and not something to fall out of a missing check.
 */
@RestController
@RequestMapping("/api/v1/applications")
@PreAuthorize("hasRole('ADOPTER')")
@RequiredArgsConstructor
public class AdopterApplicationController {

    private final ApplicationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationRow apply(@Valid @RequestBody ApplyRequest request) {
        return service.apply(request);
    }

    @GetMapping("/mine")
    public List<ApplicationRow> mine() {
        return service.mine();
    }

    /**
     * Take one back.
     *
     * Not a DELETE: the row stays as WITHDRAWN. A shelter that spent time on
     * an application should be able to see it was withdrawn rather than find a
     * gap, and adoptions may already reference nothing here but the audit does.
     */
    @PostMapping("/{id}/withdraw")
    public ApplicationRow withdraw(@PathVariable Long id) {
        return service.withdraw(id);
    }
}
