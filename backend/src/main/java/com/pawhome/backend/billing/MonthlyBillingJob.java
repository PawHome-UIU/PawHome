package com.pawhome.backend.billing;

import com.pawhome.backend.daycare.DaycareService;
import com.pawhome.backend.daycare.DaycareSubscription;
import com.pawhome.backend.daycare.DaycareSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

/**
 * The sweep that bills every live daycare subscription, once a month.
 *
 * WHY A TIMER AND NOT AN EVENT, which is the same question CareDueJob answers:
 * every other write in this system is somebody doing something -- a stay
 * completed, an application approved. A month arriving is nobody doing
 * anything. There is no request to hang the bill off, and the only thing that
 * can notice is a clock.
 *
 * IT RUNS ON THE 1st AND BILLS THE MONTH THAT HAS JUST STARTED -- charging for
 * the month ahead, not the one behind. That is what a subscription is: you pay
 * for September in September, and cancelling on the 10th does not un-bill it.
 * (Refunds are outside this system entirely; so is payment.)
 *
 * TWO STEPS, AND THE ORDER MATTERS.
 *
 *   1. RETIRE first. A subscription with an agreed end_date in the past is
 *      still ACTIVE until something notices, and billing it would charge for a
 *      month after it finished. One UPDATE for the whole sweep.
 *   2. BILL what is left.
 *
 * SAFE TO RUN AGAIN BY HAND, which somebody always does after it half-fails at
 * 03:00. uq_slip_period (subscription_id, period_from) makes a second slip for
 * the same month impossible, and BillingService checks for the existing one
 * first so the re-run is quiet rather than a wall of constraint violations.
 *
 * ONE TRANSACTION PER SUBSCRIPTION, NOT ONE FOR THE SWEEP. @Transactional is
 * on BillingService.issueForSubscription, and this method is deliberately NOT
 * annotated: a single transaction around two thousand subscriptions would mean
 * one bad row -- a shelter deleted mid-sweep, say -- rolling back everybody
 * else's bill. Each slip stands or falls alone, and the log says which failed.
 *
 * THE EXPIRY STEP IS ITS OWN TRANSACTION for the same reason, and it lives on
 * DaycareService rather than here -- not for tidiness, but because
 * @Transactional only works across a bean boundary. A job calling its own
 * annotated helper gets no transaction at all.
 */
@Component
@RequiredArgsConstructor
public class MonthlyBillingJob {

    private static final Logger log = LoggerFactory.getLogger(MonthlyBillingJob.class);

    private final DaycareSubscriptionRepository subscriptions;
    // Both are separate beans, and that is load-bearing rather than tidy: a
    // @Transactional method called on `this` never passes through the Spring
    // proxy, so the annotation silently does nothing. See DaycareService
    // .retireEnded for the longer version.
    private final DaycareService daycare;
    private final BillingService billing;

    /**
     * 03:00 UTC on the 1st of every month.
     *
     * THE HOUR IS NOT ARBITRARY: it is outside every plausible working day, so
     * the notifications land before anybody opens the app rather than while a
     * staff member is mid-checkout. The cron is a property so it can be pointed
     * at "every minute" for a demo without editing code -- which is the only
     * honest way to show a monthly job to a marker who has ten minutes.
     */
    @Scheduled(cron = "${app.billing.monthly-cron:0 0 3 1 * *}", zone = "UTC")
    public void billTheMonth() {
        YearMonth month = YearMonth.now();
        int retired = daycare.retireEnded(month.atDay(1));

        List<DaycareSubscription> due = subscriptions.findActiveOnOrBefore(month.atEndOfMonth());
        if (due.isEmpty()) {
            log.info("Monthly billing for {}: nothing to bill ({} retired)", month, retired);
            return;
        }

        int issued = 0, failed = 0;
        for (DaycareSubscription sub : due) {
            try {
                billing.issueForSubscription(sub, month);
                issued++;
            } catch (RuntimeException e) {
                // One subscription failing must not stop the other 1,999.
                // Logged with the id, because "something went wrong" at 03:00
                // is not something anybody can act on in the morning.
                failed++;
                log.error("Monthly billing failed for subscription {} ({}): {}",
                        sub.getId(), month, e.toString());
            }
        }
        log.info("Monthly billing for {}: {} slip(s) issued, {} failed, {} retired",
                month, issued, failed, retired);
    }

}
