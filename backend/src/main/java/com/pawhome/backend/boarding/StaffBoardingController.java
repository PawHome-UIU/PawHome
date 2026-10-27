package com.pawhome.backend.boarding;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// ChargeLine is nested in BillingDtos, so the BoardingDtos wildcard below
// does not reach it. Named explicitly -- the addCharge body is the same
// validated shape the check-out dialog sends, so a charge cannot be legal at
// the counter and illegal on Tuesday.
import com.pawhome.backend.billing.BillingDtos.ChargeLine;

import static com.pawhome.backend.boarding.BoardingDtos.*;

/**
 * The boarding board.
 *
 * STAFF ONLY, matching the /api/v1/staff/** URL rule rather than widening it:
 * an admin has no shelter, so shelterScope() returns null and this board would
 * silently become every shelter's stays in an API written for one. The same
 * argument as StaffPetController and StaffApplicationController, written out
 * again because the temptation to add ADMIN here is identical.
 *
 * NO DELETE. A stay ends as COMPLETED, DECLINED or CANCELLED and stays --
 * boarding_updates and boarding_instructions cascade from it, so removing one
 * would take the record of the animal's week with it.
 */
@RestController
@RequestMapping("/api/v1/staff/boarding")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffBoardingController {

    private final BoardingService service;

    @GetMapping
    public List<BoardingRow> list() {
        return service.forShelter();
    }

    @GetMapping("/counts")
    public BoardingCounts counts() {
        return service.counts();
    }

    @GetMapping("/{id}")
    public BoardingDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    /** The rooms this request could go in, for these dates and this species. */
    @GetMapping("/{id}/suites")
    public List<SuiteRow> suites(@PathVariable Long id) {
        return service.suitesFor(id);
    }

    /**
     * Accept, under the lock. See BoardingService.accept.
     *
     * The body is optional: suite_id is nullable, so a shelter that has not
     * decided which room yet can still say yes.
     */
    @PostMapping("/{id}/accept")
    public BoardingRow accept(@PathVariable Long id,
                              @RequestBody(required = false) AcceptRequest request) {
        return service.accept(id, request);
    }

    @PostMapping("/{id}/decline")
    public BoardingRow decline(@PathVariable Long id,
                               @Valid @RequestBody DeclineBoarding request) {
        return service.decline(id, request);
    }

    @PostMapping("/{id}/check-in")
    public BoardingRow checkIn(@PathVariable Long id) {
        return service.checkIn(id);
    }

    /**
     * The pet goes home and the bill is written.
     *
     * THE BODY IS OPTIONAL, because most stays are just the nights -- but when
     * there is medication or extra food to charge for, this is the only moment
     * anybody knows about it. There is no "add a charge to an existing slip"
     * endpoint: a slip is a document that was handed over, and editing one
     * after the fact is how a customer ends up owing more than the paper says.
     */
    @PostMapping("/{id}/complete")
    public BoardingRow complete(@PathVariable Long id,
                                @Valid @RequestBody(required = false) CompleteBoarding request) {
        return service.complete(id, request);
    }

    /**
     * Move the pet to a different room.                            [V17]
     *
     * POST rather than PUT, to match every other verb in this controller.
     * PUT would suggest an idempotent replacement of the whole stay; this
     * changes one field and writes a note to the owner as a side effect, which
     * is an action rather than an assignment.
     */
    @PostMapping("/{id}/suite")
    public BoardingRow moveSuite(@PathVariable Long id,
                                 @Valid @RequestBody MoveSuite request) {
        return service.moveSuite(id, request);
    }

    /**
     * Charge for something while the pet is still here.            [V17]
     *
     * THE POINT OF IT NOT BEING PART OF COMPLETE. Before this, the only place
     * to record an expense was the check-out dialog, so a fortnight of feeding
     * and medication had to be remembered by whoever happened to be handing
     * the animal back. These accrue as they happen and complete() collects
     * them.
     *
     * ChargeLine, THE SAME SHAPE THE CHECK-OUT DIALOG SENDS. One validated
     * record for both, so a charge cannot be legal at the counter and illegal
     * on Tuesday.
     */
    @PostMapping("/{id}/charges")
    @ResponseStatus(HttpStatus.CREATED)
    public ChargeRow addCharge(@PathVariable Long id,
                               @Valid @RequestBody ChargeLine request) {
        return service.addCharge(id, request);
    }

    /**
     * Take a charge back off, before the bill exists.
     *
     * A REAL DELETE, unlike most of this API, and the service explains why: a
     * mistyped charge on a bill nobody has been handed is a typo, not a record
     * of anything. Once the slip is issued this is refused -- the line is on
     * the customer's bill by then.
     */
    @DeleteMapping("/{id}/charges/{chargeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCharge(@PathVariable Long id, @PathVariable Long chargeId) {
        service.removeCharge(id, chargeId);
    }

    /**
     * Post an update, with an optional photo.
     *
     * multipart/form-data for the reason the pet photo upload is: base64 in a
     * JSON body inflates every image by a third and has to be held in memory
     * whole at both ends. The text fields ride along as parts rather than
     * becoming a second request that could half-succeed.
     */
    @PostMapping(path = "/{id}/updates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateRow postUpdate(@PathVariable Long id,
                                @RequestParam UpdateType updateType,
                                @RequestParam String note,
                                @RequestPart(value = "photo", required = false) MultipartFile photo) {
        return service.postUpdate(id, updateType, note, photo);
    }
}
