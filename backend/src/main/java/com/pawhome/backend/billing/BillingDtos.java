package com.pawhome.backend.billing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class BillingDtos {

    private BillingDtos() {}

    /**
     * What a slip says. The whole of it -- this record IS the printed page.
     *
     * KIND IS DERIVED, NOT STORED. ck_slip_source guarantees exactly one of
     * the two source columns is set, so a `kind` column would be a third copy
     * of a fact the other two already state, and the first one to disagree
     * would be the one on screen.
     *
     * NOTHING ABOUT PAYMENT. No amount paid, no method, no balance. The slip
     * says what is owed; the cash is handed over at the counter and this
     * system does not model that.
     */
    @Builder
    public record SlipRow(
            Long id,
            String slipNo,
            SlipKind kind,
            Long ownerId,
            String ownerName,
            String ownerEmail,
            Long shelterId,
            String shelterName,
            String shelterAddress,
            String shelterPhone,
            Long petId,
            String petName,
            /** Set on a boarding slip only. */
            Long boardingRequestId,
            /** Set on a daycare slip only. */
            Long subscriptionId,
            /** Null unless this is a daycare slip. */
            String slotLabel,
            LocalDate periodFrom,
            LocalDate periodTo,
            int units,
            /** "night" / "nights" / "month". What `units` counts, spelled out. */
            String unitLabel,
            BigDecimal unitRate,
            BigDecimal total,
            LocalDateTime issuedAt,
            /** Null when the monthly job issued it rather than a person. */
            String issuedByName,

            // ---- how the total breaks down --------------------------------
            //
            // BOTH COMPUTED IN SQL, NOT STORED. base is units x unit_rate and
            // extras is the sum of the lines; `total` is what was actually
            // charged, and it is stored because a slip is a document. Showing
            // all three lets a list say "3 nights plus extras" without
            // fetching every line for every row.
            /** units x unit_rate: the nights, or the month. */
            BigDecimal baseTotal,
            /** The sum of the extra lines. Zero when there are none. */
            BigDecimal extrasTotal,
            /** How many extra lines, so a list can hint at them cheaply. */
            int itemCount
    ) {}

    /**
     * One extra charge, as the printed bill shows it.
     *
     * lineTotal COMES FROM THE DATABASE. It is a generated column, so it cannot
     * disagree with the quantity and price printed beside it -- which is the
     * whole reason it is not computed here.
     */
    public record SlipItemRow(
            Long id,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {}

    /**
     * A slip with its lines. The printable page.
     *
     * SEPARATE FROM SlipRow FOR THE REASON BoardingDetail IS SEPARATE FROM
     * BoardingRow: a list of bills that carried its lines would be one extra
     * query per card, and a list shows a dozen. The lines are fetched once,
     * for the one bill somebody actually opened.
     */
    public record SlipDetail(SlipRow slip, List<SlipItemRow> items) {}

    /**
     * Which system a slip came from.
     *
     * Not a column -- see SlipRow. Two values, and they are the two source
     * foreign keys.
     */
    public enum SlipKind { BOARDING, DAYCARE }

    /**
     * An extra charge, as staff type it at the counter.
     *
     * NO lineTotal. The database computes it, so accepting one here would be
     * offering the caller a number the server is going to ignore -- and the
     * first time the two disagreed, somebody would trust the wrong one.
     */
    public record ChargeLine(
            @NotBlank(message = "Say what the charge is for.")
            @Size(max = 120, message = "Keep the description short enough to read on a bill.")
            String description,
            @NotNull @DecimalMin(value = "0.01", message = "A charge needs a quantity.")
            @Digits(integer = 6, fraction = 2) BigDecimal quantity,
            @NotNull @DecimalMin(value = "0.00", message = "A price cannot be negative.")
            @Digits(integer = 8, fraction = 2) BigDecimal unitPrice
    ) {}

    /** The shelter's takings strip. Counted, never stored. */
    public record SlipTotals(long slips, BigDecimal total,
                             long boardingSlips, long daycareSlips) {}
}
