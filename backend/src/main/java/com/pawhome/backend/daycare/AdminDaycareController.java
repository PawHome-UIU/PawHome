package com.pawhome.backend.daycare;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.pawhome.backend.daycare.DaycareDtos.*;

/**
 * Daycare across the network, for an admin.
 *
 * SAME REASON AS AdminSlipController: /api/v1/staff/** is STAFF-only in
 * SecurityConfig, and the convention it states is that admin access to the
 * same data goes through /api/v1/admin/**, with the shelter named.
 *
 * NO OCCUPANCY ENDPOINT HERE, and that is not an oversight. Occupancy is "how
 * full are MY three bands", and an admin has no shelter -- the honest
 * network-wide version of that question is a per-shelter breakdown, which is
 * what /daycare/offers already returns and what the analytics module is for.
 * A single set of three numbers summed across every shelter would mean
 * nothing: the capacity it was measured against differs per shelter.
 */
@RestController
@RequestMapping("/api/v1/admin/daycare")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDaycareController {

    private final DaycareService service;

    /**
     * One shelter's subscriptions. shelterId is REQUIRED here, unlike on the
     * slips list: a subscription only means anything against the shelter whose
     * band it occupies, and an unfiltered dump of every shelter's rows is a
     * screen nobody asked for.
     */
    @GetMapping("/subscriptions")
    public List<SubscriptionRow> forShelter(@RequestParam Long shelterId) {
        if (shelterId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say which shelter's subscriptions you want.");
        }
        return service.forShelter(shelterId);
    }
}
