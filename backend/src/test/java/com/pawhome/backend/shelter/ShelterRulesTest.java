package com.pawhome.backend.shelter;

import com.pawhome.backend.security.JwtAuthFilter;
import com.pawhome.backend.security.JwtService;
import com.pawhome.backend.security.LoginRateLimitFilter;
import com.pawhome.backend.security.SecurityConfig;
import com.pawhome.backend.user.ApprovalStatus;
import com.pawhome.backend.user.Role;
import com.pawhome.backend.user.User;
import com.pawhome.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.pawhome.backend.shelter.ShelterDtos.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Who may reach the shelter endpoints, and what an anonymous caller is handed.
 *
 * The second half is the one worth having. GET /api/v1/shelters is public --
 * it has to be, because a staff member picks their shelter before they have an
 * account -- and an endpoint that is open by design is exactly where a field
 * gets leaked by accident. `publicListCarriesNoContactDetails` fails the day
 * somebody widens that response to ShelterRow.
 *
 * NO DATABASE. The service is mocked; this is about the rules, not about SQL.
 * The tokens are real ones minted by the real JwtService, because faking the
 * authentication is the step that would skip what is being tested.
 */
@WebMvcTest(controllers = {ShelterController.class, AdminShelterController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         LoginRateLimitFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-48-bytes-long-for-hs384-ok",
        "app.jwt.expiration-ms=3600000",
        "app.cors.allowed-origins=http://localhost:5174",
})
class ShelterRulesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean UserRepository userRepository;
    @MockitoBean ShelterService shelterService;

    private static final long EVERGREEN = 1L;

    private static ShelterOption option() {
        return new ShelterOption(EVERGREEN, "Evergreen Paws", "Portland", "OR",
                new BigDecimal("25.00"));
    }

    /**
     * POSITIONAL, because ShelterRow has no @Builder -- so adding a component
     * to the record breaks this line, which is the point. V13 added two
     * (daycareMonthlyPrice, daycareSlotCapacity) between boardingRateDaily and
     * logoPath, and this is where that change has to be acknowledged.
     */
    private static ShelterRow row() {
        return new ShelterRow(EVERGREEN, "Evergreen Paws", "hi@evergreen.org",
                "555-0100", "1 Fir St", "Portland", "OR", "97201",
                // capacity, petCount, staffCount, suiteCount, then V16's
                // suitePlaces -- three rooms holding eight animals between
                // them, which is a different fact from "three rooms".
                "Portland, OR", 50, 28, 6, 3, 8, ShelterStatus.ACTIVE,
                "A shelter.", new BigDecimal("25.00"),
                // V13: daycare price, then pets per band.
                new BigDecimal("1500.00"), 20,
                null,
                LocalDateTime.now(),
                // V15: animals physically on the premises. NOT petCount above
                // -- that one is the shelter's books, and rendering it under
                // the label "Pets in Care" is the bug this column fixes.
                12L);
    }

    private static User user(long id, Role role, Long shelterId) {
        return User.builder().id(id).fullName("T").email("t" + id + "@x.com")
                .passwordHash("x").role(role).shelterId(shelterId)
                .approvalStatus(ApprovalStatus.APPROVED)
                .emailVerified(true).active(true).build();
    }

    private String tokenFor(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return jwtService.issue(user);
    }

    @BeforeEach
    void noUserByDefault() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------- the public list

    @Nested
    @DisplayName("GET /api/v1/shelters is open, and deliberately thin")
    class PublicList {

        @Test
        @DisplayName("an anonymous caller gets the list, because registration needs it")
        void anonymousMayList() throws Exception {
            when(shelterService.options()).thenReturn(List.of(option()));
            mvc.perform(get("/api/v1/shelters"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("Evergreen Paws"));
        }

        @Test
        @DisplayName("LEAK: it carries no email, phone, capacity or occupancy")
        void publicListCarriesNoContactDetails() throws Exception {
            // The guard against somebody "simplifying" the controller by
            // returning ShelterRow. Only what the two pickers need goes out;
            // anything else is a shelter's private detail handed to an
            // unauthenticated caller.
            when(shelterService.options()).thenReturn(List.of(option()));
            mvc.perform(get("/api/v1/shelters"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].email").doesNotExist())
               .andExpect(jsonPath("$[0].phone").doesNotExist())
               .andExpect(jsonPath("$[0].capacity").doesNotExist())
               .andExpect(jsonPath("$[0].petCount").doesNotExist())
               .andExpect(jsonPath("$[0].staffCount").doesNotExist())
               .andExpect(jsonPath("$[0].description").doesNotExist())
               // boardingRateDaily DOES go out, and deliberately: ShelterOption
               // carries it so the boarding form can show a nightly price
               // without a second round trip. This assertion used to say
               // doesNotExist, which stopped being true when that component was
               // added and had been failing ever since. A nightly rate is a
               // price list, not a private detail.
               .andExpect(jsonPath("$[0].boardingRateDaily").value(25.00))
               // V13. The daycare price and per-band capacity live on
               // ShelterRow, NOT on ShelterOption -- daycare has its own
               // /daycare/offers endpoint, which is authenticated and carries
               // live occupancy. If somebody ever widens this DTO, this is
               // where they find out.
               .andExpect(jsonPath("$[0].daycareMonthlyPrice").doesNotExist())
               .andExpect(jsonPath("$[0].daycareSlotCapacity").doesNotExist());
        }

        @Test
        @DisplayName("only GET is open -- POST to the same path is not")
        void publicPathIsGetOnly() throws Exception {
            mvc.perform(post("/api/v1/shelters")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------ the matrix

    @Nested
    @DisplayName("admin endpoints")
    class AdminArea {

        @Test
        @DisplayName("an anonymous caller gets 401, not an HTML login page")
        void anonymousIsRefused() throws Exception {
            mvc.perform(get("/api/v1/admin/shelters")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ADOPTER and STAFF are refused; only ADMIN gets in")
        void onlyAdmin() throws Exception {
            String adopter = tokenFor(user(10L, Role.ADOPTER, null));
            String staff = tokenFor(user(20L, Role.STAFF, EVERGREEN));
            String admin = tokenFor(user(30L, Role.ADMIN, null));

            mvc.perform(get("/api/v1/admin/shelters").header("Authorization", "Bearer " + adopter))
               .andExpect(status().isForbidden());
            // A staff member runs a shelter and still may not manage the
            // network. This is the one people assume is allowed.
            mvc.perform(get("/api/v1/admin/shelters").header("Authorization", "Bearer " + staff))
               .andExpect(status().isForbidden());

            when(shelterService.list()).thenReturn(List.of(row()));
            mvc.perform(get("/api/v1/admin/shelters").header("Authorization", "Bearer " + admin))
               .andExpect(status().isOk());
        }

        @Test
        @DisplayName("there is no DELETE -- a shelter is deactivated, never removed")
        void noDeleteMapping() throws Exception {
            String admin = tokenFor(user(31L, Role.ADMIN, null));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/admin/shelters/1")
                            .header("Authorization", "Bearer " + admin))
               .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("a create with capacity 0 is refused before it reaches the CHECK")
        void capacityIsValidated() throws Exception {
            String admin = tokenFor(user(32L, Role.ADMIN, null));
            mvc.perform(post("/api/v1/admin/shelters")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"X","email":"x@x.org","phone":"1","addressLine":"a",
                         "city":"c","state":"s","zip":"1","capacity":0}
                        """))
               .andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------- /mine

    @Nested
    @DisplayName("GET /api/v1/shelters/mine")
    class Mine {

        @Test
        @DisplayName("STAFF may read their own shelter")
        void staffMayReadTheirOwn() throws Exception {
            String staff = tokenFor(user(21L, Role.STAFF, EVERGREEN));
            when(shelterService.mine()).thenReturn(row());
            mvc.perform(get("/api/v1/shelters/mine").header("Authorization", "Bearer " + staff))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.email").value("hi@evergreen.org"));
        }

        @Test
        @DisplayName("an adopter is refused, and so is an admin -- they have no shelter")
        void othersAreRefused() throws Exception {
            String adopter = tokenFor(user(11L, Role.ADOPTER, null));
            String admin = tokenFor(user(33L, Role.ADMIN, null));

            mvc.perform(get("/api/v1/shelters/mine").header("Authorization", "Bearer " + adopter))
               .andExpect(status().isForbidden());
            // Not an oversight: an admin has no shelter_id, so "mine" has no
            // answer for them. They read the same row through /admin/shelters.
            mvc.perform(get("/api/v1/shelters/mine").header("Authorization", "Bearer " + admin))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("/mine is NOT covered by the public matcher")
        void mineRequiresAToken() throws Exception {
            // The public rule is the exact path /api/v1/shelters. If it were
            // ever widened to /api/v1/shelters/** this test is what notices.
            mvc.perform(get("/api/v1/shelters/mine")).andExpect(status().isUnauthorized());
        }
    }
}
