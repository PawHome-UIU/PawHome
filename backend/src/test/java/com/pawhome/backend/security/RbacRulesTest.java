package com.pawhome.backend.security;

import com.pawhome.backend.auth.AuthController;
import com.pawhome.backend.auth.AuthService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Does the RBAC actually hold, through the real filter chain?
 *
 * ScopeGuardTest checks the guard in isolation. This checks the thing a request
 * really meets: JwtAuthFilter, then the SecurityConfig rules, then the
 * controller. A rule that is correct in the class and wrong in the config would
 * pass there and fail here.
 *
 * NO DATABASE. UserRepository is mocked, so this runs anywhere and tests
 * authorisation rather than persistence. The tokens are real ones minted by the
 * real JwtService -- nothing here fakes an authentication, because faking it is
 * exactly the step that would skip what we want to test.
 *
 * The probe controller below exists only in this test. Real admin endpoints now
 * exist (/api/v1/admin/users), but a probe keeps this file testing the RULES
 * rather than whatever a real controller happens to return.
 */
@WebMvcTest(controllers = {AuthController.class, RbacRulesTest.Probe.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         LoginRateLimitFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-48-bytes-long-for-hs384-ok",
        "app.jwt.expiration-ms=3600000",
        "app.cors.allowed-origins=http://localhost:5174",
})
class RbacRulesTest {

    /** Stands in for the controllers this increment has not written yet. */
    @RestController
    static class Probe {
        @GetMapping("/api/v1/admin/probe")  String admin()  { return "admin"; }
        @GetMapping("/api/v1/staff/probe")  String staff()  { return "staff"; }
        @GetMapping("/api/v1/me/probe")     String anyAuth() { return "any"; }
        @GetMapping("/api/v1/pets")         String pets()   { return "pets"; }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean UserRepository userRepository;
    @MockitoBean AuthService authService;
    // AuthController depends on this; without the mock the context will not
    // start and every test in the class errors before it runs.
    @MockitoBean com.pawhome.backend.auth.VerificationService verificationService;

    private static final long EVERGREEN = 1L;

    private String tokenFor(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return jwtService.issue(user);
    }

    private static User user(long id, Role role, Long shelterId) {
        return User.builder().id(id).fullName("T").email("t" + id + "@x.com")
                .passwordHash("x").role(role).shelterId(shelterId)
                .approvalStatus(ApprovalStatus.APPROVED)
                // Required since verification joined the enabled rule. The
                // builder defaults it to false, so leaving it out silently
                // disables every test user and turns each expected 200 into
                // a 401 that looks like an RBAC failure.
                .emailVerified(true)
                .active(true).build();
    }

    @BeforeEach
    void noUserByDefault() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------ anonymous

    @Nested
    @DisplayName("with no token")
    class Anonymous {

        @Test
        @DisplayName("the guest catalogue is reachable")
        void publicPetsOk() throws Exception {
            mvc.perform(get("/api/v1/pets")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("everything else is 401, not 200 and not an HTML login page")
        void protectedIs401() throws Exception {
            mvc.perform(get("/api/v1/me/probe")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/staff/probe")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/probe")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a garbage token is 401, not 500")
        void garbageTokenIs401() throws Exception {
            mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer not-a-jwt"))
               .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------ the matrix

    @Nested
    @DisplayName("role matrix")
    class Matrix {

        @Test
        @DisplayName("ADOPTER: own area yes, staff no, admin no")
        void adopter() throws Exception {
            String t = tokenFor(user(10L, Role.ADOPTER, null));
            mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isOk());
            mvc.perform(get("/api/v1/staff/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("STAFF: staff area yes, admin no")
        void staff() throws Exception {
            String t = tokenFor(user(20L, Role.STAFF, EVERGREEN));
            mvc.perform(get("/api/v1/staff/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isOk());
            // The one that catches a copy-pasted hasAnyRole.
            mvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN: admin area yes, staff area NO")
        void admin() throws Exception {
            String t = tokenFor(user(30L, Role.ADMIN, null));
            mvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isOk());
            mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isOk());

            // Deliberate. An admin has no shelter_id, so a staff endpoint has
            // nothing to scope them to: shelterScope() returns null and the
            // query would hand back every shelter through an API written for
            // one. Admin access to the same data goes through /admin/**.
            mvc.perform(get("/api/v1/staff/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isForbidden());
        }
    }

    // --------------------------------------------- the enabled rule, per request

    @Nested
    @DisplayName("the two-flag enabled rule is enforced on every request")
    class Enabled {

        @Test
        @DisplayName("a PENDING staff member holds a valid token and is still refused")
        void pendingIsRefused() throws Exception {
            User u = user(40L, Role.STAFF, EVERGREEN);
            String t = tokenFor(u);
            // Valid while approved...
            mvc.perform(get("/api/v1/staff/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isOk());

            // ...and refused the moment approval is withdrawn, with the SAME
            // token. This is the whole reason the filter reloads the user: if
            // role and status were signed into the token, this would still pass.
            u.setApprovalStatus(ApprovalStatus.PENDING);
            mvc.perform(get("/api/v1/staff/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("deactivating an account takes effect immediately, not at token expiry")
        void deactivatedIsRefused() throws Exception {
            User u = user(41L, Role.ADOPTER, null);
            String t = tokenFor(u);
            mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isOk());

            u.setActive(false);
            mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an unconfirmed email is refused, even when approved and active")
        void unverifiedIsRefused() throws Exception {
            User u = user(43L, Role.ADOPTER, null);
            String t = tokenFor(u);
            mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isOk());

            // The third flag, checked on every request like the other two.
            u.setEmailVerified(false);
            mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a token for a deleted user is refused")
        void deletedUserIsRefused() throws Exception {
            User u = user(42L, Role.ADMIN, null);
            String t = jwtService.issue(u);           // signed, but the repo returns empty
            mvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + t))
               .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------ token forging

    @Nested
    @DisplayName("a token cannot be forged into a higher role")
    class Forging {

        @Test
        @DisplayName("editing the role claim does not grant admin")
        void tamperedClaimIsIgnored() throws Exception {
            // The signature makes editing the payload impossible without
            // detection -- but even if it were possible, nothing on the server
            // reads the role claim. Authority comes from the row.
            User adopter = user(50L, Role.ADOPTER, null);
            String t = tokenFor(adopter);

            String[] parts = t.split("\\.");
            String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"sub\":\"50\",\"role\":\"ADMIN\",\"exp\":" +
                     (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes());
            String forged = parts[0] + "." + forgedPayload + "." + parts[2];

            mvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + forged))
               .andExpect(status().isUnauthorized());   // signature no longer verifies
        }

        @Test
        @DisplayName("a token signed with a different key is refused")
        void wrongKeyIsRefused() throws Exception {
            JwtService other = new JwtService(
                    "a-completely-different-secret-also-48-bytes-long-ok!!", 3_600_000L);
            other.init();
            User admin = user(51L, Role.ADMIN, null);
            when(userRepository.findById(51L)).thenReturn(Optional.of(admin));

            mvc.perform(get("/api/v1/admin/probe")
                    .header("Authorization", "Bearer " + other.issue(admin)))
               .andExpect(status().isUnauthorized());
        }
    }
}
