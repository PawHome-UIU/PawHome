package com.pawhome.backend.billing;

import com.pawhome.backend.boarding.BoardingRequest;
import com.pawhome.backend.daycare.DaycareSlot;
import com.pawhome.backend.daycare.DaycareSubscription;
import com.pawhome.backend.notification.NotificationService;
import com.pawhome.backend.notification.NotificationType;
import com.pawhome.backend.security.ScopeGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.pawhome.backend.billing.BillingDtos.*;

/**
 * Issuing and reading slips.
 *
 * A SLIP IS THE END OF SOMETHING, NEVER THE START. Nothing here creates work:
 * boarding calls issueForBoarding() as part of checking a pet out, and the
 * monthly job calls issueForSubscription() for a month that has arrived. There
 * is no "create slip" endpoint, because a slip for a stay that has not
 * happened is an invoice for nothing.
 *
 * NO PAYMENT ANYTHING. No gateway, no card, no PAID flag, no balance. The slip
 * records what is owed; the money is cash at the counter. This was decided, not
 * forgotten -- see BillingSlip and the note in V13.
 *
 * WHY THIS SERVICE DEPENDS ON ENTITIES AND NOT ON SERVICES. BoardingService
 * calls into this class, so this class must not call back into BoardingService
 * or Spring cannot construct either of them. It takes the already-loaded
 * BoardingRequest and DaycareSubscription instead -- which is also why
 * issueForBoarding() is not @Transactional in its own right: it runs INSIDE
 * the caller's transaction, and that is the whole point. A slip that commits
 * while the check-out rolls back is a bill for a stay the system says never
 * ended.
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillingSlipRepository slips;
    private final BillingSlipItemRepository items;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    // ============================================================ issuing

    /**
     * Bill a completed stay.
     *
     * CALLED FROM INSIDE BoardingService.complete()'s TRANSACTION. Not
     * annotated @Transactional itself: Spring's default propagation joins the
     * caller's transaction, and a separate one would let the slip commit while
     * the check-out rolls back.
     *
     * BILLS WHAT THE PET ACTUALLY STAYED. period_to is picked_up_on, the column
     * V13 added, not end_date. A pet collected two days early is billed for
     * three nights, not five -- and a pet collected two days LATE is billed for
     * seven, which is the other half of the same honesty and the reason
     * ck_boarding_picked_up has no upper bound.
     *
     * MINIMUM ONE NIGHT. Dropped off and collected the same morning is nought
     * nights by subtraction, which ck_slip_units refuses -- correctly, since a
     * slip for nothing is not a slip. The floor is applied here, explicitly,
     * rather than left to surface as a constraint violation.
     *
     * IDEMPOTENT. A double-clicked Complete gets the existing slip back. The
     * read is the pleasant path; uq_slip_boarding is the guarantee, because
     * the check and the insert are not atomic with one another. Note what that
     * means for the extra lines: the SECOND click adds none, because it never
     * reaches the insert. Re-billing is not a thing you do by clicking twice.
     *
     * EXTRAS ARE WRITTEN AFTER THE SLIP, IN THE SAME TRANSACTION, and the total
     * is the sum of both. The lines need the slip's id, so there is no order in
     * which this could be one statement -- but there is also no instant a
     * customer could be shown a slip whose lines had not committed.
     */
    public BillingSlip issueForBoarding(BoardingRequest b, String petName, Long issuedBy,
                                        List<ChargeLine> extras) {
        BillingSlip existing = slips.findForBoarding(b.getId()).orElse(null);
        if (existing != null) return existing;

        LocalDate collected = b.getPickedUpOn() == null ? LocalDate.now() : b.getPickedUpOn();
        long nights = ChronoUnit.DAYS.between(b.getStartDate(), collected);
        short units = (short) Math.max(1, nights);

        BigDecimal rate = b.getDailyRate() == null ? BigDecimal.ZERO : b.getDailyRate();
        BigDecimal base = rate.multiply(BigDecimal.valueOf(units));
        BigDecimal extrasTotal = totalOf(extras);

        BillingSlip slip = slips.save(BillingSlip.builder()
                .slipNo(boardingSlipNo(b.getId(), collected))
                .ownerId(b.getOwnerId())
                .shelterId(b.getShelterId())
                .boardingRequestId(b.getId())
                .periodFrom(b.getStartDate())
                .periodTo(collected)
                .units(units)
                .unitRate(rate)
                // Recomputed from the ACTUAL nights, not copied from
                // estimated_cost -- that figure was quoted against the booked
                // dates and is exactly what this is meant to correct. Plus
                // whatever the shelter is charging for on top.
                .total(base.add(extrasTotal))
                .issuedBy(issuedBy)
                .build());

        writeLines(slip.getId(), extras);

        notifications.raise(b.getOwnerId(), NotificationType.SLIP_ISSUED,
                "Your bill for " + petName + "'s stay",
                slip.getTotal() + " for " + units + (units == 1 ? " night" : " nights")
                + ", " + b.getStartDate() + " to " + collected
                // Named, not hidden: a total larger than nights x rate with no
                // explanation is the line a customer queries at the counter.
                + (extrasTotal.signum() > 0 ? ", plus " + extrasTotal + " in extras" : "")
                + ".",
                "/app/slips");

        log.info("Issued boarding slip {} for stay {} ({} night(s), total {})",
                slip.getSlipNo(), b.getId(), units, slip.getTotal());
        return slip;
    }

    /**
     * Bill one subscription for one month.
     *
     * @param month the month being billed. period_from is its first day, which
     *              is what makes uq_slip_period mean "one slip per month".
     * @return the slip, or the one that already existed. Never null.
     *
     * A FULL MONTH WHATEVER DAY THEY JOINED. Proration was considered and not
     * done: `total` is free-form (V13 deliberately has no CHECK tying it to
     * units * unit_rate), so a prorated first month can be introduced later
     * without touching the schema. The simple rule is the one that can be
     * explained at the counter.
     *
     * SAFE TO RE-RUN. Somebody always re-runs the monthly job by hand after it
     * fails halfway; the read short-circuits and uq_slip_period catches the
     * race.
     */
    @Transactional
    public BillingSlip issueForSubscription(DaycareSubscription sub, YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        BillingSlip existing = slips.findForSubscriptionMonth(sub.getId(), from).orElse(null);
        if (existing != null) return existing;

        BigDecimal price = sub.getMonthlyPrice() == null ? BigDecimal.ZERO : sub.getMonthlyPrice();

        // NO EXTRAS ON A DAYCARE SLIP. The monthly job runs at 03:00 with
        // nobody at the counter to have charged for anything; extras are a
        // thing that happens when an animal is handed back.
        BillingSlip slip = slips.save(BillingSlip.builder()
                .slipNo(daycareSlipNo(sub.getId(), from))
                .ownerId(sub.getOwnerId())
                .shelterId(sub.getShelterId())
                .subscriptionId(sub.getId())
                .periodFrom(from)
                .periodTo(to)
                .units((short) 1)
                .unitRate(price)
                .total(price)
                // NULL: the job issued this, not a person. fk_slip_issuer is
                // ON DELETE SET NULL, so the column already means "nobody".
                .issuedBy(null)
                .build());

        notifications.raise(sub.getOwnerId(), NotificationType.SLIP_ISSUED,
                "Your daycare bill for " + month,
                price + " for the " + sub.getSlot().label() + " slot, "
                + from + " to " + to + ".",
                "/app/slips");

        return slip;
    }

    // ============================================================ reading

    /** The caller's own slips, newest first. Reads idx_slip_owner. */
    @Transactional(readOnly = true)
    public List<SlipRow> mine() {
        return jdbc.query(SELECT_ROW + " WHERE bs.owner_id = ? ORDER BY bs.issued_at DESC, bs.id DESC",
                ROW_MAPPER, scopeGuard.require().id());
    }

    /**
     * One shelter's slips. STAFF sees their own; ADMIN sees the network, or
     * one shelter if they name it.
     *
     * THE NULL SCOPE IS "EVERY SHELTER", NOT "NO SHELTER" -- the one way to
     * misuse ScopeGuard.shelterScope(). Written as `? IS NULL OR` so both
     * cases are one query and one index plan.
     */
    @Transactional(readOnly = true)
    public List<SlipRow> forShelter(Long shelterId) {
        Long scope = scopeGuard.shelterScope();
        Long target = scope != null ? scope : shelterId;
        if (target != null) scopeGuard.requireShelter(target);
        return jdbc.query(SELECT_ROW + """
                 WHERE (? IS NULL OR bs.shelter_id = ?)
                 ORDER BY bs.issued_at DESC, bs.id DESC
                """, ROW_MAPPER, target, target);
    }

    /** The takings strip above the staff list. */
    @Transactional(readOnly = true)
    public SlipTotals totals(Long shelterId) {
        Long scope = scopeGuard.shelterScope();
        Long target = scope != null ? scope : shelterId;
        if (target != null) scopeGuard.requireShelter(target);
        return jdbc.queryForObject("""
                SELECT  COUNT(*)                                   AS slips,
                        COALESCE(SUM(total), 0)                    AS total,
                        -- COALESCE on the two SUMs as well as on the total:
                        -- SUM over nought rows is NULL, not 0, and a shelter
                        -- that has billed nothing yet is the normal first day.
                        COALESCE(SUM(boarding_request_id IS NOT NULL), 0) AS boarding_slips,
                        COALESCE(SUM(subscription_id     IS NOT NULL), 0) AS daycare_slips
                  FROM  billing_slips
                 WHERE  (? IS NULL OR shelter_id = ?)
                """, (rs, i) -> new SlipTotals(rs.getLong("slips"), rs.getBigDecimal("total"),
                        rs.getLong("boarding_slips"), rs.getLong("daycare_slips")),
                target, target);
    }

    /** One slip and its lines: the printable page. */
    @Transactional(readOnly = true)
    public SlipDetail detail(Long id) {
        SlipRow slip = get(id);
        return new SlipDetail(slip, items.findForSlip(id).stream()
                .map(it -> new SlipItemRow(it.getId(), it.getDescription(),
                        it.getQuantity(), it.getUnitPrice(), it.getLineTotal()))
                .toList());
    }

    /** One slip, without its lines. Scope-checked; the detail builds on it. */
    @Transactional(readOnly = true)
    public SlipRow get(Long id) {
        List<SlipRow> found = jdbc.query(SELECT_ROW + " WHERE bs.id = ?", ROW_MAPPER, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such slip.");
        }
        SlipRow slip = found.get(0);
        // Either it is yours, or it belongs to your shelter. Neither check
        // alone is right: an owner has no shelter, and staff are not the owner.
        if (!slip.ownerId().equals(scopeGuard.require().id())) {
            scopeGuard.requireShelter(slip.shelterId());
        }
        return slip;
    }

    /**
     * Write the extra lines. Empty and null both mean "no extras".
     *
     * sortOrder IS THE INDEX IN THE LIST the staff member typed, so the bill
     * prints in the order they entered it rather than in whatever order the
     * rows come back.
     */
    private void writeLines(Long slipId, List<ChargeLine> extras) {
        if (extras == null || extras.isEmpty()) return;
        short order = 0;
        for (ChargeLine line : extras) {
            if (line == null) continue;
            items.save(BillingSlipItem.builder()
                    .slipId(slipId)
                    .description(line.description().trim())
                    .quantity(line.quantity())
                    .unitPrice(line.unitPrice())
                    .sortOrder(order++)
                    .build());
        }
    }

    /**
     * What the extra lines come to.
     *
     * COMPUTED HERE AND STORED ON THE SLIP, even though the database computes
     * each line_total itself. The alternative -- leaving total to be summed at
     * read time -- would mean editing a line silently rewrote a bill the
     * customer was handed last week.
     */
    private static BigDecimal totalOf(List<ChargeLine> extras) {
        if (extras == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (ChargeLine line : extras) {
            if (line == null) continue;
            sum = sum.add(line.quantity().multiply(line.unitPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return sum;
    }

    // ========================================================== internals

    /**
     * PW-B-2026-09-000123 -- boarding request 123, stay ending September 2026.
     *
     * Unique because uq_slip_boarding already allows only one slip per request,
     * so the id alone would do; the month is there to be readable, not to
     * disambiguate. See the note at the end of V13 for why this is derived
     * rather than counted.
     */
    private static String boardingSlipNo(Long boardingRequestId, LocalDate periodTo) {
        return String.format("PW-B-%04d-%02d-%06d",
                periodTo.getYear(), periodTo.getMonthValue(), boardingRequestId);
    }

    /** PW-D-2026-09-000045 -- subscription 45, billed for September 2026. */
    private static String daycareSlipNo(Long subscriptionId, LocalDate periodFrom) {
        return String.format("PW-D-%04d-%02d-%06d",
                periodFrom.getYear(), periodFrom.getMonthValue(), subscriptionId);
    }

    private static final String SELECT_ROW = """
        SELECT  bs.id, bs.slip_no, bs.owner_id, o.full_name AS owner_name,
                o.email AS owner_email,
                bs.shelter_id, s.name AS shelter_name,
                s.address_line AS shelter_address, s.phone AS shelter_phone,
                bs.boarding_request_id, bs.subscription_id,
                d.slot AS slot,
                -- The pet is reached through whichever source this slip has.
                -- COALESCE rather than two columns: ck_slip_source guarantees
                -- exactly one of the two joins produced a row.
                COALESCE(br.pet_id, d.pet_id) AS pet_id,
                p.name AS pet_name,
                bs.period_from, bs.period_to, bs.units, bs.unit_rate, bs.total,
                bs.issued_at, iss.full_name AS issued_by_name,
                bs.units * bs.unit_rate AS base_total,
                -- COALESCE: SUM over nought lines is NULL, and a slip with no
                -- extras is the normal case, not an edge one.
                COALESCE((SELECT SUM(li.line_total) FROM billing_slip_items li
                           WHERE li.slip_id = bs.id), 0)        AS extras_total,
                (SELECT COUNT(*) FROM billing_slip_items li2
                  WHERE li2.slip_id = bs.id)                    AS item_count
        FROM        billing_slips         bs
        JOIN        users                 o   ON o.id   = bs.owner_id
        JOIN        shelters              s   ON s.id   = bs.shelter_id
        -- Both LEFT, because exactly one of them matches on any given row.
        LEFT JOIN   boarding_requests     br  ON br.id  = bs.boarding_request_id
        LEFT JOIN   daycare_subscriptions d   ON d.id   = bs.subscription_id
        LEFT JOIN   pets                  p   ON p.id   = COALESCE(br.pet_id, d.pet_id)
        -- LEFT, because issued_by is NULL on every slip the monthly job wrote.
        LEFT JOIN   users                 iss ON iss.id = bs.issued_by
        """;

    private static final RowMapper<SlipRow> ROW_MAPPER = (rs, i) -> {
        Long boardingId = rs.getObject("boarding_request_id") == null ? null
                : rs.getLong("boarding_request_id");
        int units = rs.getInt("units");
        String slotName = rs.getString("slot");
        return SlipRow.builder()
                .id(rs.getLong("id"))
                .slipNo(rs.getString("slip_no"))
                .kind(boardingId != null ? SlipKind.BOARDING : SlipKind.DAYCARE)
                .ownerId(rs.getLong("owner_id"))
                .ownerName(rs.getString("owner_name"))
                .ownerEmail(rs.getString("owner_email"))
                .shelterId(rs.getLong("shelter_id"))
                .shelterName(rs.getString("shelter_name"))
                .shelterAddress(rs.getString("shelter_address"))
                .shelterPhone(rs.getString("shelter_phone"))
                .petId(rs.getObject("pet_id") == null ? null : rs.getLong("pet_id"))
                .petName(rs.getString("pet_name"))
                .boardingRequestId(boardingId)
                .subscriptionId(rs.getObject("subscription_id") == null ? null
                        : rs.getLong("subscription_id"))
                .slotLabel(slotName == null ? null : DaycareSlot.valueOf(slotName).label())
                .periodFrom(rs.getDate("period_from").toLocalDate())
                .periodTo(rs.getDate("period_to").toLocalDate())
                .units(units)
                // What the number counts, so the printed line reads as English
                // rather than as "1 units".
                .unitLabel(boardingId != null ? (units == 1 ? "night" : "nights")
                                              : (units == 1 ? "month" : "months"))
                .unitRate(rs.getBigDecimal("unit_rate"))
                .total(rs.getBigDecimal("total"))
                .issuedAt(rs.getTimestamp("issued_at").toLocalDateTime())
                .issuedByName(rs.getString("issued_by_name"))
                .baseTotal(rs.getBigDecimal("base_total"))
                .extrasTotal(rs.getBigDecimal("extras_total"))
                .itemCount(rs.getInt("item_count"))
                .build();
    };
}
