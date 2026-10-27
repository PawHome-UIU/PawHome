package com.pawhome.backend.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the admin Analytics screen reads.
 *
 * ONE RESPONSE, NOT SEVEN ENDPOINTS. Every figure on that page describes the
 * same period and the same shelter filter, and splitting them into separate
 * calls means seven chances for the charts to disagree with each other -- a
 * donut totalling 5,204 beside a tile reading 5,190 because the two requests
 * straddled a submission. One query set, one instant, one answer.
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {}

    /**
     * A headline figure and how it moved.
     *
     * `delta` IS NULLABLE AND THAT IS LOAD-BEARING. It is the percentage change
     * against the immediately preceding period of the same length, which is
     * undefined when that period had nothing in it -- and "+100%" against a
     * base of zero is a number somebody quotes in a meeting. Null means the
     * tile shows no badge at all.
     */
    public record Tile(String value, Integer delta) {}

    public record MonthPoint(String month, long adoptions) {}

    /** name matches a key in the frontend's APPLICATION_STATUS palette. */
    public record StatusSlice(String name, long value) {}

    public record ShelterCount(String shelter, long adoptions) {}

    /**
     * One bar of the stacked species chart.
     *
     * The four counts are named fields rather than a map because the frontend
     * reads them by key -- PET_STATUS's keys -- and a map would let a typo
     * compile and render an empty segment.
     */
    public record SpeciesRow(
            String species,
            long available,
            long pending,
            long adopted,
            long unavailable
    ) {}

    public record AdoptionRecord(
            Long id,
            String pet,
            String shelter,
            String adopter,
            LocalDate date,
            BigDecimal fee,
            /**
             * Days from the application being submitted to the adoption being
             * recorded. The one number on this page that measures the
             * shelter's own speed rather than its volume.
             */
            long days
    ) {}

    /**
     * One month of billing, split by what was billed for.
     *
     * NIGHTS AND MONTHS ARE NEVER ADDED TOGETHER. billing_slips.units means
     * nights on a boarding slip and months on a subscription one, so a single
     * "units" figure would be adding nights to months -- a number nobody can
     * name. v_shelter_revenue keeps them apart and so does this.
     *
     * revenuePerNight IS DERIVED HERE, NOT STORED. boardingTotal / nightsBilled,
     * and null rather than zero when no nights were billed: "no boarding this
     * month" and "we earned nothing per night" are different statements, and
     * dividing by zero to say the second one is how a chart grows a spike at
     * the origin.
     */
    public record RevenueMonth(
            String month,
            long boardingSlips,
            long nightsBilled,
            BigDecimal boardingTotal,
            /** Null when nightsBilled is 0. See the note above. */
            BigDecimal revenuePerNight,
            long daycareSlips,
            long monthsBilled,
            BigDecimal daycareTotal,
            BigDecimal totalBilled
    ) {}

    /** One shelter's billing, for the admin's per-shelter table. */
    public record ShelterRevenue(
            Long shelterId,
            String shelter,
            long nightsBilled,
            BigDecimal boardingTotal,
            BigDecimal revenuePerNight,
            long activeSubscriptions,
            BigDecimal daycareTotal,
            BigDecimal totalBilled
    ) {}

    public record Analytics(
            Tile adoptions,
            Tile applications,
            Tile approvalRate,
            Tile activePets,
            List<MonthPoint> adoptionsByMonth,
            List<StatusSlice> applicationsByStatus,
            List<ShelterCount> adoptionsByShelter,
            List<SpeciesRow> petsBySpecies,
            List<AdoptionRecord> adoptionRecords,
            /**
             * V19. Billing, which the admin had no view of at all -- the three
             * AdminSlipController endpoints existed and nothing called them.
             *
             * READ FROM v_shelter_revenue, so the split between a boarding slip
             * and a daycare one is defined once in the schema rather than in
             * every query that needs it.
             */
            Tile boardingRevenue,
            Tile daycareRevenue,
            Tile nightsBoarded,
            Tile activeSubscriptions,
            List<RevenueMonth> revenueByMonth,
            List<ShelterRevenue> revenueByShelter
    ) {}
}
