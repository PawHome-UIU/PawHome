package com.pawhome.backend.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.billing.BillingDtos.*;

/**
 * What a shelter has billed.
 *
 * READ-ONLY, for the same reason the owner's side is, and it is worth being
 * explicit about the one thing that is NOT here: there is no "issue a slip by
 * hand" endpoint. A boarding slip is written by checking the pet out and a
 * daycare slip by the monthly job; a hand-issued one would be a bill with no
 * stay and no month behind it, and ck_slip_source would refuse it anyway --
 * every slip must point at a boarding request or a subscription.
 *
 * STAFF ONLY, AND NOT hasAnyRole('STAFF','ADMIN') -- which is what this said
 * first, and which never worked. SecurityConfig gates the whole prefix:
 *
 *     .requestMatchers("/api/v1/staff/**").hasRole("STAFF")
 *
 * The URL rule runs in the filter chain, long before @PreAuthorize is
 * consulted, so an admin calling this was refused there and the wider
 * annotation was dead code describing a route that did not exist. RbacRulesTest
 * asserts exactly that: "ADMIN: admin area yes, staff area NO".
 *
 * It is not a hole -- the stricter rule won -- but a comment claiming admins
 * can read this is worse than no comment. Admin access to the same data goes
 * through AdminSlipController, under /api/v1/admin/**, where the shelter is
 * named explicitly. That is the convention SecurityConfig spells out.
 *
 * THERE IS NO shelterId PARAMETER HERE. A staff caller's scope comes from
 * their own account; letting a query parameter override it would undo
 * ScopeGuard with a URL.
 */
@RestController
@RequestMapping("/api/v1/staff/slips")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffSlipController {

    private final BillingService service;

    // null: the service reads the caller's shelter from ScopeGuard, and for a
    // STAFF caller shelterScope() never returns null.
    @GetMapping
    public List<SlipRow> list() {
        return service.forShelter(null);
    }

    @GetMapping("/totals")
    public SlipTotals totals() {
        return service.totals(null);
    }

    @GetMapping("/{id}")
    public SlipDetail get(@PathVariable Long id) {
        return service.detail(id);
    }
}
