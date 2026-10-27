package com.pawhome.backend.admin;

import com.pawhome.backend.auth.AuthController;
import com.pawhome.backend.auth.AuthService;
import com.pawhome.backend.auth.VerificationService;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Who may invite, who may accept, and who may move somebody between shelters.
 *
 * The transfer cases live here rather than in a file of their own because they
 * share every line of this setup and test the same question about the same
 * controller. If a third admin-user feature arrives, split the file then.
 *
 * The two halves pull in opposite directions and both are easy to get wrong:
 *
 *   POST /api/v1/admin/users/invite   ADMIN only. It creates an APPROVED staff
 *                                     account attached to a shelter -- if any
 *                                     authenticated caller could reach it,
 *                                     every adopter could grant themselves
 *                                     staff access to any shelter they liked.
 *
 *   POST /api/v1/auth/accept-invite   ANONYMOUS. The invited person has no
 *                                     account to sign in with; the token in
 *                                     the link is the whole of their
 *                                     authority. A 401 here means every
 *                                     invitation ever sent is a dead end.
 *
 * NO DATABASE. The services are mocked; this is about the rules, not the SQL.
 * The tokens are real ones minted by the real JwtService, because faking the
 * authentication is precisely the step that would skip what is being tested.
 */
@WebMvcTest(controllers = {AdminUserController.class, AuthController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         LoginRateLimitFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-48-bytes-long-for-hs384-ok",
        "app.jwt.expiration-ms=3600000",
        "app.cors.allowed-origins=http://localhost:5174",
})
class InviteRulesTest {

    private static final String MOVE_BODY = """
            {"shelterId":2}
            """;

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean UserRepository userRepository;
    @MockitoBean AdminUserService adminUserService;
    @MockitoBean InviteService inviteService;
    @MockitoBean AuthService authService;
    @MockitoBean VerificationService verificationService;

    private static final String INVITE_BODY = """
            {"fullName":"Maria Santos","email":"maria@evergreen.org",
             "shelterId":1,"jobTitle":"Shelter Manager"}
            """;

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

    private static AdminUserDtos.AdminUserRow invitedRow() {
        return new AdminUserDtos.AdminUserRow(7L, "Maria Santos",
                "maria@evergreen.org", Role.STAFF, ApprovalStatus.APPROVED,
                false, true, 1L, "Evergreen Paws", "Shelter Manager",
                LocalDateTime.now(), true);
    }

    @BeforeEach
    void noUserByDefault() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------- inviting

    @Nested
    @DisplayName("POST /api/v1/admin/users/invite is ADMIN only")
    class Inviting {

        @Test
        @DisplayName("an admin may invite, and gets the new row back")
        void adminMayInvite() throws Exception {
            when(inviteService.invite(anyString(), anyString(), anyLong(), any()))
                    .thenReturn(invitedRow());

            mvc.perform(post("/api/v1/admin/users/invite")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(INVITE_BODY))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.email").value("maria@evergreen.org"))
               .andExpect(jsonPath("$.invitePending").value(true));
        }

        @Test
        @DisplayName("PRIVILEGE ESCALATION: staff may not invite")
        void staffMayNotInvite() throws Exception {
            // The whole point. This endpoint mints an APPROVED staff account
            // for a shelter of the caller's choosing.
            mvc.perform(post("/api/v1/admin/users/invite")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(INVITE_BODY))
               .andExpect(status().isForbidden());

            verifyNoInteractions(inviteService);
        }

        @Test
        @DisplayName("PRIVILEGE ESCALATION: an adopter may not invite")
        void adopterMayNotInvite() throws Exception {
            mvc.perform(post("/api/v1/admin/users/invite")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(INVITE_BODY))
               .andExpect(status().isForbidden());

            verifyNoInteractions(inviteService);
        }

        @Test
        @DisplayName("an anonymous caller may not invite")
        void anonymousMayNotInvite() throws Exception {
            mvc.perform(post("/api/v1/admin/users/invite")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(INVITE_BODY))
               .andExpect(status().isUnauthorized());

            verifyNoInteractions(inviteService);
        }

        @Test
        @DisplayName("a nameless or address-less invitation is refused before it reaches the service")
        void validationRunsFirst() throws Exception {
            mvc.perform(post("/api/v1/admin/users/invite")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"fullName":"","email":"not-an-address","shelterId":null}
                            """))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(inviteService);
        }

        @Test
        @DisplayName("resending is ADMIN only too")
        void resendIsAdminOnly() throws Exception {
            mvc.perform(post("/api/v1/admin/users/7/resend-invite")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L))))
               .andExpect(status().isForbidden());

            mvc.perform(post("/api/v1/admin/users/7/resend-invite")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null))))
               .andExpect(status().isOk());

            verify(inviteService, times(1)).resendInvite(7L);
        }
    }

    // ---------------------------------------------------------- transferring

    @Nested
    @DisplayName("POST /api/v1/admin/users/{id}/shelter is ADMIN only")
    class Transferring {

        @Test
        @DisplayName("an admin may move a staff member")
        void adminMayMove() throws Exception {
            when(adminUserService.setShelter(anyLong(), anyLong()))
                    .thenReturn(invitedRow());

            mvc.perform(post("/api/v1/admin/users/7/shelter")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MOVE_BODY))
               .andExpect(status().isOk());

            verify(adminUserService).setShelter(7L, 2L);
        }

        @Test
        @DisplayName("PRIVILEGE ESCALATION: staff may not move anybody, themselves least of all")
        void staffMayNotMove() throws Exception {
            // A staff member who could call this could walk their own account
            // into any shelter in the network -- ScopeGuard reads shelter_id
            // off the user row, so it would be the only check they had to pass.
            mvc.perform(post("/api/v1/admin/users/2/shelter")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MOVE_BODY))
               .andExpect(status().isForbidden());

            verify(adminUserService, never()).setShelter(anyLong(), anyLong());
        }

        @Test
        @DisplayName("an adopter and an anonymous caller may not move anybody")
        void othersMayNotMove() throws Exception {
            mvc.perform(post("/api/v1/admin/users/7/shelter")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MOVE_BODY))
               .andExpect(status().isForbidden());

            mvc.perform(post("/api/v1/admin/users/7/shelter")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MOVE_BODY))
               .andExpect(status().isUnauthorized());

            verify(adminUserService, never()).setShelter(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a body with no shelter is a 400, and the service is never asked")
        void shelterIsRequired() throws Exception {
            mvc.perform(post("/api/v1/admin/users/7/shelter")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"shelterId":null}
                            """))
               .andExpect(status().isBadRequest());

            verify(adminUserService, never()).setShelter(anyLong(), anyLong());
        }
    }

    // ------------------------------------------------------------ accepting

    @Nested
    @DisplayName("POST /api/v1/auth/accept-invite is open, because it has to be")
    class Accepting {

        @Test
        @DisplayName("an anonymous caller may accept -- the token is the authority")
        void anonymousMayAccept() throws Exception {
            mvc.perform(post("/api/v1/auth/accept-invite")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"token":"whatever-the-link-carried","password":"Str0ngPassw0rd"}
                            """))
               .andExpect(status().isOk());

            verify(inviteService).accept("whatever-the-link-carried", "Str0ngPassw0rd");
        }

        @Test
        @DisplayName("a missing token is a 400, and the service is never asked")
        void tokenIsRequired() throws Exception {
            mvc.perform(post("/api/v1/auth/accept-invite")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"token":"","password":"Str0ngPassw0rd"}
                            """))
               .andExpect(status().isBadRequest());

            verify(inviteService, never()).accept(anyString(), anyString());
        }

        @Test
        @DisplayName("LEAK: the response carries no token and no session")
        void acceptingDoesNotSignYouIn() throws Exception {
            // Setting a password must NOT hand back a JWT. The link may have
            // been read by whoever reached the inbox rather than the person it
            // was meant for; ending at the login form makes that one loud step
            // instead of a silent session.
            mvc.perform(post("/api/v1/auth/accept-invite")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"token":"t","password":"Str0ngPassw0rd"}
                            """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.token").doesNotExist())
               .andExpect(jsonPath("$.accessToken").doesNotExist())
               .andExpect(cookie().doesNotExist("JSESSIONID"));
        }
    }
}
