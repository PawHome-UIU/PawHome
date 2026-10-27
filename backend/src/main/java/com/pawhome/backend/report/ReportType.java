package com.pawhome.backend.report;

/**
 * Maps the `reports.report_type` ENUM in V1__init.sql.
 *
 * Each value is a different QUESTION, not a different formatting of the same
 * rows -- which is why ReportService has four builders rather than one
 * parameterised query.
 */
public enum ReportType {

    /** Every adoption in the window, one row per animal placed. */
    ADOPTION_SUMMARY("Adoption Summary"),

    /** One row per shelter: volume, approval rate, average time to decide. */
    SHELTER_EFFICIENCY("Shelter Efficiency"),

    /**
     * Who is in the shelters RIGHT NOW.
     *
     * The odd one out: it is a snapshot, so the date window does not filter
     * its rows. The window is still recorded on the row, because "the
     * inventory as it stood when I ran this" is what makes an old file
     * meaningful.
     */
    PET_INVENTORY("Pet Inventory"),

    /** The headline numbers, as label/value pairs for a board or a funder. */
    QUARTERLY_IMPACT("Quarterly Impact");

    private final String label;

    ReportType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** "Adoption_Summary", the stem of the download's filename. */
    public String fileStem() {
        return label.replace(' ', '_');
    }
}
