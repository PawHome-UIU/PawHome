package com.pawhome.backend.daycare;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.daycare.DaycareDtos.*;

/**
 * Daycare, from the customer's side.
 *
 * ADOPTER ONLY, for the reason OwnerBoardingController is: a staff member
 * signing their own pet up at their own shelter would then be the person
 * managing the slot they are occupying, and ScopeGuard would wave it through
 * because the shelter matches. That is a policy decision if anyone ever wants
 * it, not something to fall out of a missing role check.
 *
 * "Adopter" is the role name, not a claim about the animal -- V9 added OWNED
 * so a household pet that was never adopted from anyone can be boarded, and
 * daycare serves exactly the same pets.
 */
@RestController
@RequestMapping("/api/v1/daycare")
@PreAuthorize("hasRole('ADOPTER')")
@RequiredArgsConstructor
public class OwnerDaycareController {

    private final DaycareService service;

    /**
     * Every shelter running daycare, with live slot occupancy.
     *
     * NOT UNDER /shelters. A shelter's own endpoint answers "who are you";
     * this answers "where can I buy this, and is there room" -- the occupancy
     * numbers come from daycare_subscriptions, not from the shelter row.
     */
    @GetMapping("/offers")
    public List<DaycareOffer> offers() {
        return service.offers();
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionRow subscribe(@Valid @RequestBody SubscribeRequest request) {
        return service.subscribe(request);
    }

    @GetMapping("/subscriptions/mine")
    public List<SubscriptionRow> mine() {
        return service.mine();
    }

    @GetMapping("/subscriptions/{id}")
    public SubscriptionRow get(@PathVariable Long id) {
        return service.get(id);
    }

    /**
     * Stop it.
     *
     * NOT A DELETE, for the reason withdrawing an application is not one: the
     * row stays as CANCELLED with the date it stopped, because the slips
     * already issued against it point at it and a deleted parent would either
     * take them with it or be refused by fk_slip_sub. A subscription that
     * existed and ended is not the same as one that never happened.
     */
    @PostMapping("/subscriptions/{id}/cancel")
    public SubscriptionRow cancel(@PathVariable Long id) {
        return service.cancel(id);
    }
}
