package com.pawhome.backend.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.pawhome.backend.analytics.AnalyticsDtos.Analytics;

/**
 * Network-wide figures. ADMIN ONLY, and the path says so.
 *
 * There is deliberately no staff equivalent. Everything here compares shelters
 * to one another -- which shelter placed the most animals, how one approval
 * rate sits against the network's -- and that is a governance view, not an
 * operational one. A staff member's own numbers are on their own screens,
 * already scoped to their shelter by ScopeGuard.
 *
 * The shelter parameter NARROWS the network view for an admin; it is not an
 * authorisation boundary, which is why it can be absent and why it is safe
 * for it to be. Compare /shelters/mine/suites, where the shelter is taken
 * from the token precisely because it IS the boundary.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AnalyticsService service;

    /**
     * @param months  how far back to look; the service refuses anything
     *                outside 1..60 rather than letting a typo scan the table.
     * @param shelter null for the whole network.
     */
    @GetMapping
    public Analytics overview(@RequestParam(defaultValue = "6") int months,
                              @RequestParam(required = false) Long shelter) {
        return service.overview(months, shelter);
    }
}
