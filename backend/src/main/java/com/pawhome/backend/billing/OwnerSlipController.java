package com.pawhome.backend.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.billing.BillingDtos.*;

/**
 * The customer's bills.
 *
 * READ-ONLY, AND THERE IS NO WAY TO MAKE THAT NOT TRUE. There is no POST, no
 * PUT and no DELETE anywhere in this controller, because a slip is issued by
 * the thing it bills -- checking a pet out, or the monthly job -- and never by
 * somebody asking for one. A customer-facing endpoint that created a bill
 * would be an invoice for a stay that had not happened.
 *
 * NOTHING HERE PAYS ANYTHING EITHER. This project has no payment processing at
 * all; the cash is handed over at the counter. See BillingSlip for why that is
 * a decision rather than an omission.
 */
@RestController
@RequestMapping("/api/v1/slips")
@PreAuthorize("hasRole('ADOPTER')")
@RequiredArgsConstructor
public class OwnerSlipController {

    private final BillingService service;

    @GetMapping("/mine")
    public List<SlipRow> mine() {
        return service.mine();
    }

    /**
     * One slip, for the printable page.
     *
     * The service checks it is either yours or your shelter's before returning
     * it -- the role alone cannot answer that, which is the whole reason
     * ScopeGuard exists.
     */
    @GetMapping("/{id}")
    public SlipDetail get(@PathVariable Long id) {
        return service.detail(id);
    }
}
