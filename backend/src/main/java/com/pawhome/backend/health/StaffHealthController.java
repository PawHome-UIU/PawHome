package com.pawhome.backend.health;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.pawhome.backend.health.HealthDtos.VaccinationRow;

/**
 * The shelter's whole vaccination register.
 *
 * NOT NESTED UNDER A PET, because this one is not about a pet: it is the list
 * a staff member scans to find what is due and what has slipped. The per-pet
 * routes hang off the animal because that is where the shelter check lives;
 * this one scopes through the pet in the query instead.
 *
 * SCHEDULED FIRST, THEN BY DUE DATE, so the overdue rows are the ones on
 * screen without scrolling. Sorting by when a shot was given would put the
 * completed history first and bury the only rows that need acting on.
 */
@RestController
@RequestMapping("/api/v1/staff/vaccinations")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffHealthController {

    private final VaccinationService service;

    @GetMapping
    public List<VaccinationRow> list() {
        return service.forShelter();
    }
}
