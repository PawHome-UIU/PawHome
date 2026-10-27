package com.pawhome.backend.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.billing.BillingDtos.*;

/**
 * Billing across the whole network.
 *
 * WHY THIS EXISTS AS ITS OWN CONTROLLER rather than an extra role on the staff
 * one. SecurityConfig gates /api/v1/staff/** with hasRole("STAFF") and spells
 * out why: an admin has no shelter_id, so a staff endpoint has no shelter to
 * scope them to -- shelterScope() would return null and hand back every
 * shelter's rows through an API meant for one. Its answer is this convention:
 * "Admin access to the same data goes through /api/v1/admin/**, where the
 * shelter is named explicitly."
 *
 * So that is what shelterId is for here, and only here. Omit it and the list
 * is network-wide, which for an admin is the correct reading of
 * ScopeGuard.shelterScope() returning null -- "every shelter", never "no
 * shelter". Pass one and it narrows.
 *
 * STILL READ-ONLY. There is no hand-issue endpoint on the admin side either: a
 * slip is written by checking a pet out or by the monthly job, and one issued
 * by hand would be a bill with no stay and no month behind it. ck_slip_source
 * would refuse the row anyway.
 */
@RestController
@RequestMapping("/api/v1/admin/slips")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminSlipController {

    private final BillingService service;

    @GetMapping
    public List<SlipRow> list(@RequestParam(required = false) Long shelterId) {
        return service.forShelter(shelterId);
    }

    @GetMapping("/totals")
    public SlipTotals totals(@RequestParam(required = false) Long shelterId) {
        return service.totals(shelterId);
    }

    @GetMapping("/{id}")
    public SlipDetail get(@PathVariable Long id) {
        return service.detail(id);
    }
}
