package com.pawhome.backend.shelter;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// ONE LINE PER TYPE, NOT A WILDCARD. This controller lists what it uses, so a
// new endpoint returning a new DTO has to say so here -- which is exactly what
// caught RoomRow and OnSiteRow missing. Other services in this project use
// `ShelterDtos.*`; both styles are fine, but mixing them within one file is how
// somebody adds an import that was already covered.
import static com.pawhome.backend.shelter.ShelterDtos.OnSiteRow;
import static com.pawhome.backend.shelter.ShelterDtos.RoomRow;
import static com.pawhome.backend.shelter.ShelterDtos.ShelterOption;
import static com.pawhome.backend.shelter.ShelterDtos.ShelterRow;
import static com.pawhome.backend.shelter.ShelterDtos.SuiteRequest;
import static com.pawhome.backend.shelter.ShelterDtos.SuiteRow;

/**
 * The two shelter reads that are not admin-only.
 *
 * GET /api/v1/shelters IS OPEN, AND THAT IS THE POINT. A staff member has to
 * choose their shelter on the registration form, before they have an account
 * and therefore before they have a token. Until this existed the dropdown was
 * populated from a hard-coded JavaScript array whose ids matched nothing in
 * the database, so every staff sign-up failed on fk_users_shelter -- the
 * account could not be created at all.
 *
 * BECAUSE IT IS OPEN, IT RETURNS ShelterOption AND NOT ShelterRow. Four
 * fields: id, name, city, state. No email, no phone, no capacity, no
 * occupancy, no staff count. That is enforced by the return type rather than
 * by remembering to strip fields, because "remember to strip fields" is how
 * the next person adds a column and leaks it.
 */
@RestController
@RequestMapping("/api/v1/shelters")
@RequiredArgsConstructor
public class ShelterController {

    private final ShelterService service;

    /** Public. ACTIVE shelters only -- see ShelterService.options(). */
    @GetMapping
    public List<ShelterOption> options() {
        return service.options();
    }

    /**
     * The signed-in staff member's own shelter, in full.
     *
     * Not under /staff/** because it is not a listing that needs scoping to a
     * shelter -- it IS the shelter, and ScopeGuard settles which one. STAFF
     * only: an admin has no shelter_id, so "mine" means nothing for them, and
     * they read the same data through /admin/shelters.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('STAFF')")
    public ShelterRow mine() {
        return service.mine();
    }

    // ------------------------------------------------------- my own suites
    //
    // NOTE THERE IS NO SHELTER ID IN ANY OF THESE PATHS. That is the whole
    // security argument: the admin routes are /admin/shelters/{id}/suites and
    // have to check that {id} is the caller's, whereas here the shelter can
    // only come from the token. A staff member cannot even express a request
    // that names somebody else's shelter, so the staff branch of ScopeGuard
    // is not the only thing standing between them and another shelter's
    // rooms -- the URL grammar is.
    //
    // The suite id in the last two IS from the path, and that one is checked:
    // ShelterService.loadSuite refuses a suite belonging to another shelter.

    /**
     * Every room and who is in it right now. STAFF only, like /mine.
     *
     * NOT /mine/suites, WHICH ALREADY EXISTS AND MEANS SOMETHING ELSE. That one
     * describes the rooms; this describes the building at a moment. Folding
     * occupancy into the suites response would make create and rename compute
     * an occupant nobody asked for.
     */
    @GetMapping("/mine/rooms")
    @PreAuthorize("hasRole('STAFF')")
    public List<RoomRow> myRooms() {
        return service.myRooms();
    }

    /** Every animal on the premises, longest-staying first. */
    @GetMapping("/mine/on-site")
    @PreAuthorize("hasRole('STAFF')")
    public List<OnSiteRow> whoIsHere() {
        return service.whoIsHere();
    }

    @GetMapping("/mine/suites")
    @PreAuthorize("hasRole('STAFF')")
    public List<SuiteRow> mySuites() {
        return service.mySuites();
    }

    @PostMapping("/mine/suites")
    @PreAuthorize("hasRole('STAFF')")
    public SuiteRow addMySuite(@Valid @RequestBody SuiteRequest request) {
        return service.addMySuite(request);
    }

    @PutMapping("/mine/suites/{suiteId}")
    @PreAuthorize("hasRole('STAFF')")
    public SuiteRow updateMySuite(@PathVariable Long suiteId,
                                  @Valid @RequestBody SuiteRequest request) {
        return service.updateMySuite(suiteId, request);
    }

    /**
     * Take a room out of service. Never a delete -- a completed stay still
     * points at the suite it happened in.
     */
    @PostMapping("/mine/suites/{suiteId}/deactivate")
    @PreAuthorize("hasRole('STAFF')")
    public SuiteRow deactivateMySuite(@PathVariable Long suiteId) {
        return service.deactivateMySuite(suiteId);
    }
}
