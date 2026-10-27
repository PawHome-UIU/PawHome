package com.pawhome.backend.application;

import com.pawhome.backend.pet.PetStatus;
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
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Who applies, and who decides.
 *
 * THE TWO SIDES MUST NOT MEET, and both halves of that are tested here. An
 * adopter cannot reach the review queue -- it is the shelter's judgement of
 * them. A staff member cannot file an application -- they would be reviewing
 * their own, because ScopeGuard would happily let them: the pet is in their
 * shelter. That second one is not a hypothetical tidiness rule; it is the
 * shape of a conflict of interest that the role check is the only thing
 * preventing.
 *
 * NO DATABASE. The service is mocked; the four constraints it leans on
 * (uq_one_open_app, ck_apps_decline, uq_adoption_application and
 * ck_pets_owner_when_placed) were exercised separately against a real MySQL.
 */
@WebMvcTest(controllers = {AdopterApplicationController.class, StaffApplicationController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         LoginRateLimitFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-48-bytes-long-for-hs384-ok",
        "app.jwt.expiration-ms=3600000",
        "app.cors.allowed-origins=http://localhost:5174",
})
class ApplicationRulesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean UserRepository userRepository;
    @MockitoBean ApplicationService applicationService;

    private static final String APPLY_BODY = """
            {"petId":1,"eligibilityAttemptId":9,"applicantName":"Ada",
             "applicantEmail":"ada@x.com","applicantPhone":"555","applicantAddress":"1 St",
             "applicantCity":"Portland","homeType":"House","timeAtHome":"All day",
             "petExperience":"Two dogs previously"}
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

    private static ApplicationDtos.ApplicationRow row() {
        return ApplicationDtos.ApplicationRow.builder()
                .id(1L).petId(1L).petName("Milo").petBreed("Tabby")
                .petStatus(PetStatus.AVAILABLE)
                .shelterId(1L).shelterName("Evergreen Paws")
                .applicantId(3L).applicantName("Ada").applicantEmail("ada@x.com")
                .applicantPhone("555").applicantAddress("1 St").applicantCity("Portland")
                .homeType("House").timeAtHome("All day").petExperience("Two dogs")
                .quizScore((short) 72).quizMaxScore(100).quizPassed(true)
                .status(ApplicationStatus.PENDING)
                .submittedAt(LocalDateTime.now())
                .build();
    }

    @BeforeEach
    void noUserByDefault() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("applying is the adopter's, and only the adopter's")
    class Applying {

        @Test
        @DisplayName("an adopter may apply and read their own list")
        void adopterMay() throws Exception {
            when(applicationService.apply(any())).thenReturn(row());
            when(applicationService.mine()).thenReturn(List.of(row()));

            mvc.perform(post("/api/v1/applications")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null)))
                       .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
               .andExpect(status().isCreated());

            mvc.perform(get("/api/v1/applications/mine")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].petName").value("Milo"));
        }

        @Test
        @DisplayName("CONFLICT OF INTEREST: staff may not file an application")
        void staffMayNotApply() throws Exception {
            // ScopeGuard would let a staff member review it: the pet is in
            // their shelter. The role check is the only thing between that and
            // somebody approving their own adoption.
            mvc.perform(post("/api/v1/applications")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
               .andExpect(status().isForbidden());

            verifyNoInteractions(applicationService);
        }

        @Test
        @DisplayName("an admin may not file one either")
        void adminMayNotApply() throws Exception {
            mvc.perform(post("/api/v1/applications")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
               .andExpect(status().isForbidden());

            verifyNoInteractions(applicationService);
        }

        @Test
        @DisplayName("an anonymous caller gets 401")
        void anonymousRefused() throws Exception {
            mvc.perform(post("/api/v1/applications")
                       .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("applying with no quiz attempt is a 400 before the service is asked")
        void attemptIsRequired() throws Exception {
            mvc.perform(post("/api/v1/applications")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"petId":1,"eligibilityAttemptId":null,"applicantName":"Ada",
                             "applicantEmail":"ada@x.com","applicantPhone":"555",
                             "applicantAddress":"1 St","applicantCity":"Portland",
                             "homeType":"House","timeAtHome":"All day","petExperience":"x"}
                            """))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(applicationService);
        }
    }

    @Nested
    @DisplayName("deciding is the shelter's, and only the shelter's")
    class Deciding {

        @Test
        @DisplayName("staff may read the queue and approve")
        void staffMay() throws Exception {
            when(applicationService.forShelter()).thenReturn(List.of(row()));
            when(applicationService.approve(anyLong(), any())).thenReturn(row());

            mvc.perform(get("/api/v1/staff/applications")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L))))
               .andExpect(status().isOk());

            mvc.perform(post("/api/v1/staff/applications/1/approve")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L))))
               .andExpect(status().isOk());
        }

        @Test
        @DisplayName("LEAK: an adopter may not read the review queue")
        void adopterMayNotReview() throws Exception {
            // It carries every applicant's address, phone number and score --
            // the shelter's judgement of other people.
            mvc.perform(get("/api/v1/staff/applications")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null))))
               .andExpect(status().isForbidden());

            mvc.perform(post("/api/v1/staff/applications/1/approve")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null))))
               .andExpect(status().isForbidden());

            verifyNoInteractions(applicationService);
        }

        @Test
        @DisplayName("an admin is refused, for the shelterScope reason")
        void adminRefused() throws Exception {
            // shelterScope() is null for an admin, so this queue would become
            // every shelter's applications through an API written for one.
            mvc.perform(get("/api/v1/staff/applications")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null))))
               .andExpect(status().isForbidden());

            verifyNoInteractions(applicationService);
        }

        @Test
        @DisplayName("declining without a reason or a message is a 400")
        void declineNeedsBoth() throws Exception {
            // ck_apps_decline requires both on the row. Catching it here is
            // what turns a constraint violation into a form error.
            String token = tokenFor(user(2, Role.STAFF, 1L));

            mvc.perform(post("/api/v1/staff/applications/1/decline")
                       .header("Authorization", "Bearer " + token)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"reasonCode\":null,\"message\":\"Long enough message.\"}"))
               .andExpect(status().isBadRequest());

            mvc.perform(post("/api/v1/staff/applications/1/decline")
                       .header("Authorization", "Bearer " + token)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"reasonCode\":\"OTHER\",\"message\":\"no\"}"))
               .andExpect(status().isBadRequest());

            verify(applicationService, never()).decline(anyLong(), any());
        }
    }
}
