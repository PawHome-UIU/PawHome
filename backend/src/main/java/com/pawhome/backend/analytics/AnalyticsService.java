package com.pawhome.backend.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static com.pawhome.backend.analytics.AnalyticsDtos.*;

/**
 * The numbers behind the admin Analytics screen.
 *
 * WHAT THIS REPLACES. Every chart on that page was a hard-coded array in
 * data/admin.js -- 1,482 adoptions, 5,204 applications, a 68.5% approval rate,
 * bars for six months that never happened. It rendered convincingly, which is
 * the problem: an admin could read it, believe it, and repeat it.
 *
 * JDBC, NOT JPA, AND DELIBERATELY. These are aggregates over whole tables with
 * no entity to hydrate. Loading every adoption as a managed object to count
 * them is the classic way an analytics page becomes the slowest screen in an
 * application; each method below is one GROUP BY that the database answers
 * from an index.
 *
 * EVERY COUNT IS FILTERED BY THE SAME TWO THINGS -- a date floor and an
 * optional shelter -- and they are passed down explicitly rather than held in
 * a field, because a service field would be shared state on a singleton bean
 * and two admins with different filters would read each other's numbers.
 *
 * THE SHELTER FILTER TAKES A DIFFERENT ROUTE IN EACH QUERY, on purpose:
 *
 *   adoptions              adoptions.shelter_id      -- copied at adoption time
 *   applications, pets     pets.shelter_id           -- the pet's shelter now
 *
 * V1 denormalises shelter_id onto adoptions so "Adoptions by Shelter" keeps
 * crediting the shelter that did the work even if the animal is later
 * transferred. Reaching through the pet instead would silently move a
 * completed adoption from one shelter's column to another's.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final JdbcTemplate jdbc;

    /** How far back the page may look. A guard, not a preference. */
    private static final int MAX_MONTHS = 60;

    @Transactional(readOnly = true)
    public Analytics overview(int months, Long shelterId) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a range between 1 and " + MAX_MONTHS + " months.");
        }
        if (shelterId != null) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shelters WHERE id = ?", Integer.class, shelterId);
            if (exists == null || exists == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter.");
            }
        }

        // The window, computed ONCE and passed everywhere. Calling CURDATE()
        // inside each query would let a request that spans midnight measure two
        // different days, and the charts would not add up to the tiles.
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(months);
        LocalDate previousFrom = from.minusMonths(months);

        return new Analytics(
                adoptionTile(from, previousFrom, shelterId),
                applicationTile(from, previousFrom, shelterId),
                approvalTile(from, previousFrom, shelterId),
                activePetTile(shelterId),
                adoptionsByMonth(from, to, shelterId),
                applicationsByStatus(from, shelterId),
                adoptionsByShelter(from),
                petsBySpecies(shelterId),
                adoptionRecords(from, shelterId),
                // V19. BILLING, which the admin could not see at all.
                revenueTile(from, previousFrom, shelterId, true),
                revenueTile(from, previousFrom, shelterId, false),
                nightsTile(from, previousFrom, shelterId),
                subscriptionTile(shelterId),
                revenueByMonth(from, to, shelterId),
                revenueByShelter(from));
    }

    // ---------------------------------------------------------- the billing

    /**
     * Boarding or daycare money in the window, against the window before it.
     *
     * ONE METHOD FOR BOTH, with a boolean, because the two differ by exactly
     * which column of v_shelter_revenue to sum. Two near-identical methods is
     * how one of them ends up summing the wrong one.
     *
     * READ FROM THE VIEW, not from billing_slips. The rule "a boarding slip is
     * one where boarding_request_id is not null" lives in v_shelter_revenue and
     * is easy to get backwards -- this is the reason the view exists.
     */
    private Tile revenueTile(LocalDate from, LocalDate previousFrom,
                             Long shelterId, boolean boarding) {
        String col = boarding ? "boarding_total" : "daycare_total";
        String sql = """
                SELECT COALESCE(SUM(%s), 0)
                  FROM v_shelter_revenue
                 WHERE billed_month >= ?
                   AND (? IS NULL OR shelter_id = ?)
                """.formatted(col);
        BigDecimal now  = jdbc.queryForObject(sql, BigDecimal.class,
                from.withDayOfMonth(1), shelterId, shelterId);
        BigDecimal then = jdbc.queryForObject(sql, BigDecimal.class,
                previousFrom.withDayOfMonth(1), shelterId, shelterId);
        // `then` spans BOTH windows, so the previous one alone is the
        // difference -- the same arithmetic adoptionTile does.
        BigDecimal prev = (then == null ? BigDecimal.ZERO : then)
                .subtract(now == null ? BigDecimal.ZERO : now);
        return new Tile(money(now), delta(
                now == null ? 0 : now.longValue(),
                prev.longValue()));
    }

    /** Nights actually boarded in the window. */
    private Tile nightsTile(LocalDate from, LocalDate previousFrom, Long shelterId) {
        String sql = """
                SELECT COALESCE(SUM(nights_billed), 0)
                  FROM v_shelter_revenue
                 WHERE billed_month >= ?
                   AND (? IS NULL OR shelter_id = ?)
                """;
        Long now  = jdbc.queryForObject(sql, Long.class,
                from.withDayOfMonth(1), shelterId, shelterId);
        Long then = jdbc.queryForObject(sql, Long.class,
                previousFrom.withDayOfMonth(1), shelterId, shelterId);
        long n = now == null ? 0 : now;
        return new Tile(String.valueOf(n), delta(n, (then == null ? 0 : then) - n));
    }

    /**
     * Live daycare subscriptions. NOT from the revenue view.
     *
     * A subscription that has never been billed yet is still a live
     * subscription, and v_shelter_revenue only knows about slips. So this one
     * counts the subscriptions themselves -- the same figure
     * DaycareService.takenBySlot reports, asked without the slot breakdown.
     *
     * NO DELTA. It is a snapshot of right now, not a total over a window;
     * comparing it to "the same snapshot a month ago" would need history this
     * table does not keep, and a made-up arrow is worse than none.
     */
    private Tile subscriptionTile(Long shelterId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM daycare_subscriptions
                 WHERE status = 'ACTIVE'
                   AND (? IS NULL OR shelter_id = ?)
                """, Long.class, shelterId, shelterId);
        return new Tile(String.valueOf(n == null ? 0 : n), null);
    }

    /**
     * The revenue chart: one row per month in the window, zeros included.
     *
     * THE MONTH SPINE IS GENERATED, exactly as adoptionsByMonth does it: a
     * recursive CTE of months LEFT JOINed to the data, so a month with no
     * billing is a zero on the chart rather than a missing point. A line chart
     * that skips empty months draws a straight line through them and lies
     * about the shape.
     */
    private List<RevenueMonth> revenueByMonth(LocalDate from, LocalDate to, Long shelterId) {
        return jdbc.query("""
                WITH RECURSIVE months AS (
                    SELECT DATE_FORMAT(?, '%Y-%m-01') AS m
                    UNION ALL
                    SELECT DATE_ADD(m, INTERVAL 1 MONTH) FROM months
                     WHERE m < DATE_FORMAT(?, '%Y-%m-01')
                )
                SELECT DATE_FORMAT(months.m, '%b %Y')                  AS month,
                       COALESCE(SUM(r.boarding_slips), 0)              AS boarding_slips,
                       COALESCE(SUM(r.nights_billed), 0)               AS nights_billed,
                       COALESCE(SUM(r.boarding_total), 0.00)           AS boarding_total,
                       COALESCE(SUM(r.daycare_slips), 0)               AS daycare_slips,
                       COALESCE(SUM(r.months_billed), 0)               AS months_billed,
                       COALESCE(SUM(r.daycare_total), 0.00)            AS daycare_total,
                       COALESCE(SUM(r.total_billed), 0.00)             AS total_billed
                  FROM months
                  LEFT JOIN v_shelter_revenue r
                         ON r.billed_month = months.m
                        AND (? IS NULL OR r.shelter_id = ?)
                 GROUP BY months.m
                 ORDER BY months.m
                """, (rs, i) -> {
            long nights = rs.getLong("nights_billed");
            BigDecimal boarding = rs.getBigDecimal("boarding_total");
            return new RevenueMonth(
                    rs.getString("month"),
                    rs.getLong("boarding_slips"), nights, boarding,
                    // Null, not zero, when nothing was boarded. See the DTO.
                    nights == 0 ? null
                            : boarding.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP),
                    rs.getLong("daycare_slips"),
                    rs.getLong("months_billed"),
                    rs.getBigDecimal("daycare_total"),
                    rs.getBigDecimal("total_billed"));
        }, from, to, shelterId, shelterId);
    }

    /**
     * Per shelter, across the window. The admin's league table.
     *
     * NOT FILTERED BY shelterId, on the same reasoning as adoptionsByShelter:
     * the whole point of the table is comparing shelters, so narrowing it to
     * one would leave a table with a single row beside a chart about everybody.
     */
    private List<ShelterRevenue> revenueByShelter(LocalDate from) {
        return jdbc.query("""
                SELECT  s.id                                   AS shelter_id,
                        s.name                                 AS shelter,
                        COALESCE(SUM(r.nights_billed), 0)      AS nights_billed,
                        COALESCE(SUM(r.boarding_total), 0.00)  AS boarding_total,
                        COALESCE(SUM(r.daycare_total), 0.00)   AS daycare_total,
                        COALESCE(SUM(r.total_billed), 0.00)    AS total_billed,
                        (SELECT COUNT(*) FROM daycare_subscriptions d
                          WHERE d.shelter_id = s.id AND d.status = 'ACTIVE')
                                                               AS active_subscriptions
                FROM        shelters s
                LEFT JOIN   v_shelter_revenue r
                        ON  r.shelter_id = s.id AND r.billed_month >= ?
                GROUP BY    s.id, s.name
                ORDER BY    total_billed DESC, s.name
                """, (rs, i) -> {
            long nights = rs.getLong("nights_billed");
            BigDecimal boarding = rs.getBigDecimal("boarding_total");
            return new ShelterRevenue(
                    rs.getLong("shelter_id"), rs.getString("shelter"),
                    nights, boarding,
                    nights == 0 ? null
                            : boarding.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP),
                    rs.getLong("active_subscriptions"),
                    rs.getBigDecimal("daycare_total"),
                    rs.getBigDecimal("total_billed"));
        }, from.withDayOfMonth(1));
    }

    /** Money as the tiles print it -- no currency sign, the page adds that. */
    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------- the tiles

    private Tile adoptionTile(LocalDate from, LocalDate previousFrom, Long shelterId) {
        long now = count("""
                SELECT COUNT(*) FROM adoptions a
                 WHERE a.adopted_on >= ?
                   AND (? IS NULL OR a.shelter_id = ?)
                """, from, shelterId, shelterId);
        long before = count("""
                SELECT COUNT(*) FROM adoptions a
                 WHERE a.adopted_on >= ? AND a.adopted_on < ?
                   AND (? IS NULL OR a.shelter_id = ?)
                """, previousFrom, from, shelterId, shelterId);
        return new Tile(String.valueOf(now), delta(now, before));
    }

    private Tile applicationTile(LocalDate from, LocalDate previousFrom, Long shelterId) {
        long now = count(APPS_IN_WINDOW, from, shelterId, shelterId);
        long before = count("""
                SELECT COUNT(*) FROM adoption_applications app
                  JOIN pets p ON p.id = app.pet_id
                 WHERE app.submitted_at >= ? AND app.submitted_at < ?
                   AND (? IS NULL OR p.shelter_id = ?)
                """, previousFrom, from, shelterId, shelterId);
        return new Tile(String.valueOf(now), delta(now, before));
    }

    /**
     * Approved as a share of the applications somebody actually DECIDED.
     *
     * THE DENOMINATOR IS THE WHOLE ARGUMENT. Dividing by every application
     * would count the ones still sitting in the queue as failures, so the rate
     * would fall every time a new application arrived and rise when a shelter
     * stopped accepting them. WITHDRAWN is excluded too -- the applicant
     * changed their mind, and that is not a shelter's decision to be judged on.
     */
    private Tile approvalTile(LocalDate from, LocalDate previousFrom, Long shelterId) {
        Integer[] now = decision(from, null, shelterId);
        Integer[] before = decision(previousFrom, from, shelterId);

        Integer rateNow = rate(now[0], now[1]);
        Integer rateBefore = rate(before[0], before[1]);

        // A rate is already a percentage, so its movement is stated in POINTS,
        // not as a percentage of a percentage. 40% -> 50% is +10 here, and the
        // tile's "%" suffix is the reader's clue that this is the same unit.
        Integer change = (rateNow == null || rateBefore == null)
                ? null : rateNow - rateBefore;

        return new Tile(rateNow == null ? "--" : rateNow + "%", change);
    }

    /** {approved, decided} inside a window; `until` may be null for "to now". */
    private Integer[] decision(LocalDate from, LocalDate until, Long shelterId) {
        String bound = until == null ? "" : " AND app.submitted_at < ?";
        Object[] args = until == null
                ? new Object[]{from, shelterId, shelterId}
                : new Object[]{from, until, shelterId, shelterId};

        return jdbc.queryForObject("""
                SELECT SUM(app.status = 'APPROVED')                       AS approved,
                       SUM(app.status IN ('APPROVED','REJECTED'))         AS decided
                  FROM adoption_applications app
                  JOIN pets p ON p.id = app.pet_id
                 WHERE app.submitted_at >= ?""" + bound + """
                   AND (? IS NULL OR p.shelter_id = ?)
                """, (rs, i) -> new Integer[]{rs.getInt("approved"), rs.getInt("decided")}, args);
    }

    /**
     * Pets on the catalogue right now.
     *
     * NO DATE FILTER, because "active" is a fact about this moment rather than
     * about a period -- a pet available today was available today whichever
     * range the reader picked. Shelter pets only: a household animal is not
     * this network's inventory.
     */
    private Tile activePetTile(Long shelterId) {
        long now = count("""
                SELECT COUNT(*) FROM pets p
                 WHERE p.shelter_id IS NOT NULL
                   AND p.status IN ('AVAILABLE','PENDING')
                   AND (? IS NULL OR p.shelter_id = ?)
                """, shelterId, shelterId);
        // No previous figure to compare a live count against, so no badge.
        return new Tile(String.valueOf(now), null);
    }

    // ------------------------------------------------------------ the charts

    /**
     * Adoptions per calendar month, WITH THE EMPTY MONTHS PRESENT.
     *
     * A GROUP BY on its own returns only months that had an adoption, so a
     * quiet July simply vanishes and the bars either side sit next to each
     * other -- a chart that reads as steady activity when the truth is a gap.
     * The recursive CTE generates the calendar and LEFT JOINs the data onto
     * it, so a zero month is a zero-height bar.
     */
    private List<MonthPoint> adoptionsByMonth(LocalDate from, LocalDate to, Long shelterId) {
        return jdbc.query("""
                WITH RECURSIVE months AS (
                    SELECT DATE_FORMAT(?, '%Y-%m-01') AS m
                    UNION ALL
                    SELECT DATE_ADD(m, INTERVAL 1 MONTH) FROM months
                     WHERE m < DATE_FORMAT(?, '%Y-%m-01')
                )
                SELECT DATE_FORMAT(months.m, '%b %Y') AS month,
                       COUNT(a.id)                    AS adoptions
                  FROM months
                  LEFT JOIN adoptions a
                         ON a.adopted_on >= months.m
                        AND a.adopted_on <  DATE_ADD(months.m, INTERVAL 1 MONTH)
                        AND (? IS NULL OR a.shelter_id = ?)
                 GROUP BY months.m
                 ORDER BY months.m
                """,
                (rs, i) -> new MonthPoint(rs.getString("month"), rs.getLong("adoptions")),
                from, to, shelterId, shelterId);
    }

    /**
     * The donut, with a row for every status even at zero.
     *
     * Same reason as the empty months: a status that drops out of the legend
     * when its count reaches zero makes the chart's vocabulary change shape
     * under the reader, and "no applications were withdrawn" is information.
     * The status list is a derived table so the LEFT JOIN has something to
     * keep.
     */
    private List<StatusSlice> applicationsByStatus(LocalDate from, Long shelterId) {
        return jdbc.query("""
                SELECT s.label AS name, COUNT(app.id) AS value
                  FROM (          SELECT 'Approved'  AS label, 'APPROVED'  AS code
                        UNION ALL SELECT 'Pending',           'PENDING'
                        UNION ALL SELECT 'Rejected',          'REJECTED'
                        UNION ALL SELECT 'Withdrawn',         'WITHDRAWN') s
                  LEFT JOIN (adoption_applications app
                             JOIN pets p ON p.id = app.pet_id)
                         ON app.status = s.code
                        AND app.submitted_at >= ?
                        AND (? IS NULL OR p.shelter_id = ?)
                 GROUP BY s.label, s.code
                 ORDER BY FIELD(s.code, 'APPROVED','PENDING','REJECTED','WITHDRAWN')
                """,
                (rs, i) -> new StatusSlice(rs.getString("name"), rs.getLong("value")),
                from, shelterId, shelterId);
    }

    /**
     * Every shelter, including the ones that placed nobody.
     *
     * NOT FILTERED BY THE SHELTER PICKER, and that is the point of the chart:
     * it is the comparison between shelters, and narrowing it to one leaves a
     * single bar at 100% of itself, which says nothing.
     */
    private List<ShelterCount> adoptionsByShelter(LocalDate from) {
        return jdbc.query("""
                SELECT sh.name AS shelter, COUNT(a.id) AS adoptions
                  FROM shelters sh
                  LEFT JOIN adoptions a
                         ON a.shelter_id = sh.id AND a.adopted_on >= ?
                 GROUP BY sh.id, sh.name
                 ORDER BY adoptions DESC, sh.name
                """,
                (rs, i) -> new ShelterCount(rs.getString("shelter"), rs.getLong("adoptions")),
                from);
    }

    /**
     * Shelter pets by species and status.
     *
     * BOARDING and OWNED are not counted: V9 gives those rows shelter_id NULL
     * to mean "belongs to a household", and the WHERE clause says so outright
     * rather than relying on the status list to exclude them by accident.
     */
    private List<SpeciesRow> petsBySpecies(Long shelterId) {
        return jdbc.query("""
                SELECT p.species,
                       SUM(p.status = 'AVAILABLE')   AS available,
                       SUM(p.status = 'PENDING')     AS pending,
                       SUM(p.status = 'ADOPTED')     AS adopted,
                       SUM(p.status = 'UNAVAILABLE') AS unavailable
                  FROM pets p
                 WHERE p.shelter_id IS NOT NULL
                   AND (? IS NULL OR p.shelter_id = ?)
                 GROUP BY p.species
                 ORDER BY p.species
                """,
                (rs, i) -> new SpeciesRow(
                        rs.getString("species"),
                        rs.getLong("available"), rs.getLong("pending"),
                        rs.getLong("adopted"), rs.getLong("unavailable")),
                shelterId, shelterId);
    }

    /**
     * The most recent adoptions, longest-first by date.
     *
     * Capped rather than paged. This is the tail of a dashboard, not a ledger;
     * the full history belongs behind an export, and returning every adoption
     * the network has ever made to draw a six-row table is how a page that
     * works in a demo stops working in a year.
     */
    private List<AdoptionRecord> adoptionRecords(LocalDate from, Long shelterId) {
        return jdbc.query("""
                SELECT a.id, p.name AS pet, sh.name AS shelter, u.full_name AS adopter,
                       a.adopted_on AS adopted_on, a.fee_paid,
                       -- CAN COME OUT NEGATIVE, AND IS NOT CLAMPED HERE.
                       -- Nothing stops an adoption being dated before the
                       -- application it came from: a CHECK cannot span two
                       -- tables in MySQL, so the ordering is a service-level
                       -- invariant at adoption time and not a guarantee this
                       -- query may lean on. GREATEST(...,0) would turn a
                       -- data-entry error into a plausible "0 days" and hide
                       -- it forever; the real figure travels, and the table
                       -- renders anything negative as "--".
                       DATEDIFF(a.adopted_on, DATE(app.submitted_at)) AS days
                  FROM adoptions a
                  JOIN pets p                    ON p.id   = a.pet_id
                  JOIN shelters sh               ON sh.id  = a.shelter_id
                  JOIN users u                   ON u.id   = a.adopter_id
                  JOIN adoption_applications app ON app.id = a.application_id
                 WHERE a.adopted_on >= ?
                   AND (? IS NULL OR a.shelter_id = ?)
                 ORDER BY a.adopted_on DESC, a.id DESC
                 LIMIT 25
                """,
                (rs, i) -> new AdoptionRecord(
                        rs.getLong("id"), rs.getString("pet"), rs.getString("shelter"),
                        rs.getString("adopter"), rs.getObject("adopted_on", LocalDate.class),
                        rs.getBigDecimal("fee_paid"), rs.getLong("days")),
                from, shelterId, shelterId);
    }

    // ------------------------------------------------------------- internals

    private static final String APPS_IN_WINDOW = """
            SELECT COUNT(*) FROM adoption_applications app
              JOIN pets p ON p.id = app.pet_id
             WHERE app.submitted_at >= ?
               AND (? IS NULL OR p.shelter_id = ?)
            """;

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /**
     * Percentage change, or null when there is nothing to change from.
     *
     * "Up 100%" from a base of zero is arithmetically defensible and
     * completely useless -- one adoption in a network that made none last
     * quarter is not a doubling, it is a first. Null, and the tile shows no
     * badge at all.
     */
    private static Integer delta(long now, long before) {
        if (before == 0) return null;
        return BigDecimal.valueOf(now - before)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(before), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /** A whole-number percentage, or null when nothing was decided. */
    private static Integer rate(int part, int whole) {
        if (whole == 0) return null;
        return Math.round(part * 100f / whole);
    }
}
