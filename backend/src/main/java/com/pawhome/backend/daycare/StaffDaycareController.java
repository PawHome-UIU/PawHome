package com.pawhome.backend.daycare;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

import static com.pawhome.backend.daycare.DaycareDtos.*;

/**
 * Daycare, from the shelter's side. Read-only, and deliberately.
 *
 * STAFF DO NOT SIGN PEOPLE UP HERE. A subscription is an agreement between an
 * owner and a shelter about somebody's own pet, and every check that makes it
 * safe -- the pet is yours, the quiz is yours and you passed it -- is a check
 * about the CALLER. A staff-side create endpoint would have to take an owner
 * id and skip all three, which is a hole shaped exactly like "sign up a pet
 * that isn't theirs against a quiz they never took".
 *
 * STAFF ONLY, AND NOT hasAnyRole('STAFF','ADMIN'). SecurityConfig gates the
 * whole prefix with .requestMatchers("/api/v1/staff/**").hasRole("STAFF"), and
 * that rule runs in the filter chain before any @PreAuthorize -- so the wider
 * annotation this class used to carry described a route admins could never
 * reach. Both pages here are built around "my shelter", which an admin does
 * not have (ck_users_staff_shelter), so STAFF is also the right answer rather
 * than merely the enforced one.
 *
 * The network-wide view is AdminDaycareController, under /api/v1/admin/**.
 */
@RestController
@RequestMapping("/api/v1/staff/daycare")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffDaycareController {

    private final DaycareService service;

    // GET /occupancy WAS HERE AND IS GONE. It returned the three band tiles,
    // and /today now returns those same tiles as part of a payload that also
    // carries the arrivals board -- so the staff page fetches one endpoint
    // instead of two and the tiles cannot disagree with the list beside them.
    //
    // DELETED RATHER THAN LEFT, because an endpoint nothing calls is worse
    // than no endpoint: the next person to need this reads two implementations
    // and has to work out which one is live. DaycareService.occupancy() stays
    // -- it is what offers() uses to build the adopter signup page, which is a
    // different caller with a different shape.

    /**
     * Today's arrivals board: who is due, who is here, who has gone home,
     * and how many places the rooms actually hold.
     *
     * SEPARATE FROM /occupancy RATHER THAN FOLDED INTO IT, because
     * /occupancy's SlotOccupancy is also what the adopter signup page reads
     * through DaycareOffer -- for every shelter in the list. Attaching today's
     * attendance to it would make a browsing adopter pay for an attendance
     * query per shelter to see three numbers they cannot act on.
     */
    @GetMapping("/today")
    public ArrivalsBoard dueToday() {
        return service.dueToday();
    }

    /**
     * The subscriptions held at this caller's shelter.
     *
     * null, not a parameter: a staff caller's scope comes from their own
     * account, and letting a query parameter override it would be the whole
     * point of ScopeGuard undone by a URL.
     */
    @GetMapping("/subscriptions")
    public List<SubscriptionRow> forShelter() {
        return service.forShelter(null);
    }

    // ------------------------------------------------------ the day itself

    /**
     * Who came in on a given day. Defaults to today, which is the only day
     * anybody looks at from the desk.
     *
     * A PARAMETER RATHER THAN A SECOND ENDPOINT, because "yesterday" is the
     * same question and a /today path would have to grow a sibling the first
     * time somebody asks how busy Monday was.
     */
    @GetMapping("/attendance")
    public List<AttendanceRow> board(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.board(date);
    }

    /** Drop-off: the pet is here, and it goes in this room. */
    @PostMapping("/attendance")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceRow checkIn(@Valid @RequestBody CheckInRequest request) {
        return service.checkIn(request);
    }

    /**
     * Collection.
     *
     * NOT A DELETE. The visit happened; removing the row would erase the fact
     * that the pet was here, which is the one thing this table exists to
     * record -- and it is what "how long has this pet been with us" is counted
     * from.
     */
    @PostMapping("/attendance/{id}/check-out")
    public AttendanceRow checkOut(@PathVariable Long id) {
        return service.checkOut(id);
    }
}
