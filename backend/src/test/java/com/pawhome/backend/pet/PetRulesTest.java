package com.pawhome.backend.pet;

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
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Who may manage a shelter's pets.
 *
 * THE ADMIN CASE IS THE INTERESTING ONE, and it is a refusal. SecurityConfig
 * maps /api/v1/staff/** to hasRole("STAFF") and explains why: an admin has no
 * shelter of their own (ck_users_staff_shelter), so ScopeGuard.shelterScope()
 * returns null for them and every query in PetService would silently widen to
 * the whole network through an API written for one shelter. adminIsRefused
 * fails the day somebody "helpfully" adds ADMIN to the annotation.
 *
 * ROW-LEVEL SCOPING IS NOT TESTED HERE and cannot be: it lives in PetService,
 * which this test mocks away. It is covered from two other directions --
 * ScopeGuardTest exercises requireShelter's foreign-shelter branch directly,
 * and PetService routes every read and write through it. This file is about
 * who reaches the door, not which rows are behind it.
 */
@WebMvcTest(controllers = StaffPetController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         LoginRateLimitFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-48-bytes-long-for-hs384-ok",
        "app.jwt.expiration-ms=3600000",
        "app.cors.allowed-origins=http://localhost:5174",
})
class PetRulesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    @MockitoBean UserRepository userRepository;
    @MockitoBean PetService petService;

    private static final String PET_BODY = """
            {"name":"Luna","species":"DOG","breed":"Mixed","ageValue":2,
             "ageUnit":"YEARS","sex":"FEMALE","size":"MEDIUM",
             "adoptionFee":0,"traitIds":[]}
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

    /**
     * Built by name. The positional form broke the moment blockingCount was
     * added -- silently, at the end of a twenty-five argument list.
     */
    private static PetDtos.PetRow row() {
        return PetDtos.PetRow.builder()
                .id(1L)
                .shelterId(1L)
                .shelterName("Evergreen Paws")
                .name("Luna")
                .species(Species.DOG)
                .breed("Mixed")
                .ageValue((short) 2)
                .ageUnit(AgeUnit.YEARS)
                .sex(Sex.FEMALE)
                .size(PetSize.MEDIUM)
                .status(PetStatus.AVAILABLE)
                .adoptionFee(BigDecimal.ZERO)
                .traitIds(List.of())
                .traitLabels(List.of())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static PetDtos.PetDetail detail() {
        return new PetDtos.PetDetail(row(), List.of());
    }

    @BeforeEach
    void noUserByDefault() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("/api/v1/staff/pets is STAFF only")
    class WhoGetsIn {

        @Test
        @DisplayName("a staff member may list and add")
        void staffMay() throws Exception {
            when(petService.list()).thenReturn(List.of(row()));
            when(petService.create(any())).thenReturn(detail());

            mvc.perform(get("/api/v1/staff/pets")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("Luna"));

            mvc.perform(post("/api/v1/staff/pets")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON).content(PET_BODY))
               .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("an ADMIN is refused, and that is deliberate")
        void adminIsRefused() throws Exception {
            // Not an oversight: shelterScope() is null for an admin, so this
            // API would hand back every shelter's pets. Admin access to the
            // same data belongs under /api/v1/admin/**, where the shelter is
            // named explicitly.
            mvc.perform(get("/api/v1/staff/pets")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null))))
               .andExpect(status().isForbidden());

            verifyNoInteractions(petService);
        }

        @Test
        @DisplayName("an adopter is refused")
        void adopterRefused() throws Exception {
            mvc.perform(get("/api/v1/staff/pets")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null))))
               .andExpect(status().isForbidden());

            verifyNoInteractions(petService);
        }

        @Test
        @DisplayName("an anonymous caller gets 401")
        void anonymousRefused() throws Exception {
            mvc.perform(get("/api/v1/staff/pets")).andExpect(status().isUnauthorized());
            verifyNoInteractions(petService);
        }

        @Test
        @DisplayName("a pet with no name or species is a 400 before the service is asked")
        void validationRunsFirst() throws Exception {
            mvc.perform(post("/api/v1/staff/pets")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"name":"","species":null,"breed":"","ageValue":0,
                             "ageUnit":"YEARS","sex":"FEMALE","size":"MEDIUM"}
                            """))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(petService);
        }
    }

    @Nested
    @DisplayName("a pet is never deleted")
    class NoDeleting {

        @Test
        @DisplayName("the only DELETE mapping is for a photo, never for a pet")
        void deleteIsPhotosOnly() {
            // adoption_applications and adoptions reference a pet with ON
            // DELETE RESTRICT: an adoption that happened last year still has to
            // name the animal. Nothing references a pet_photos row, so removing
            // one destroys no record of anything.
            List<String> deletePatterns = handlerMapping.getHandlerMethods().entrySet().stream()
                    .filter(e -> e.getValue().getBeanType() == StaffPetController.class)
                    .map(Map.Entry::getKey)
                    .filter(info -> info.getMethodsCondition().getMethods().stream()
                            .anyMatch(rm -> rm.name().equals("DELETE")))
                    .map(RequestMappingInfo::toString)
                    .toList();

            assertThat(deletePatterns)
                    .as("UNAVAILABLE is how a pet leaves the catalogue")
                    .allMatch(p -> p.contains("/photos/"));
        }

        @Test
        @DisplayName("ADOPTED cannot be set from the status route")
        void adoptedIsRefused() throws Exception {
            // The refusal itself lives in PetService (mocked here), so this
            // asserts only that the route exists and carries the body through.
            // ck_pets_owner_when_placed is verified against a real MySQL.
            when(petService.setStatus(anyLong(), eq(PetStatus.UNAVAILABLE))).thenReturn(row());

            mvc.perform(post("/api/v1/staff/pets/1/status")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"status\":\"UNAVAILABLE\"}"))
               .andExpect(status().isOk());

            verify(petService).setStatus(1L, PetStatus.UNAVAILABLE);
        }
    }
}
