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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The heart, and what a guest is allowed to see.
 *
 * THE LEAK TEST IS THE ONE THAT MATTERS. GET /api/v1/pets is open to anyone,
 * and the same route answers differently once you sign in -- so the projection
 * has to be incapable of carrying anything private, not merely careful about
 * it. PublicPetRow has no owner, no view count and no application count, and
 * noPrivateFieldsForAGuest fails the day somebody "simplifies" the controller
 * by returning PetRow.
 */
@WebMvcTest(controllers = {FavoriteController.class, PublicPetController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         LoginRateLimitFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-48-bytes-long-for-hs384-ok",
        "app.jwt.expiration-ms=3600000",
        "app.cors.allowed-origins=http://localhost:5174",
})
class FavoriteRulesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean UserRepository userRepository;
    @MockitoBean FavoriteService favoriteService;
    @MockitoBean PetService petService;

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

    private static PetDtos.PublicPetRow pet() {
        return PetDtos.PublicPetRow.builder()
                .id(1L).name("Milo").species(Species.CAT).breed("Tabby")
                .ageValue((short) 1).ageUnit(AgeUnit.YEARS).sex(Sex.MALE)
                .size(PetSize.SMALL).status(PetStatus.AVAILABLE)
                .adoptionFee(new BigDecimal("45.00"))
                .photoPaths(List.of()).traitLabels(List.of("Calm"))
                .matchedTraits(List.of()).missedTraits(List.of())
                .shelterName("Evergreen Paws").shelterCity("Portland").shelterState("OR")
                .favorite(false)
                .build();
    }

    @BeforeEach
    void noUserByDefault() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("the catalogue is open, and deliberately thin")
    class Browsing {

        @Test
        @DisplayName("LEAK: a guest's pets carry no owner, view count or application count")
        void noPrivateFieldsForAGuest() throws Exception {
            when(petService.browse(isNull(), isNull())).thenReturn(List.of(pet()));

            mvc.perform(get("/api/v1/pets"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("Milo"))
               .andExpect(jsonPath("$[0].ownerId").doesNotExist())
               .andExpect(jsonPath("$[0].viewCount").doesNotExist())
               .andExpect(jsonPath("$[0].applicationCount").doesNotExist())
               .andExpect(jsonPath("$[0].blockingCount").doesNotExist())
               .andExpect(jsonPath("$[0].createdBy").doesNotExist())
               .andExpect(jsonPath("$[0].shelterId").doesNotExist());
        }

        @Test
        @DisplayName("a guest gets no match score and no hearts")
        void guestSeesNoViewerState() throws Exception {
            when(petService.browse(isNull(), isNull())).thenReturn(List.of(pet()));

            mvc.perform(get("/api/v1/pets"))
               .andExpect(jsonPath("$[0].matchPercent").doesNotExist())
               .andExpect(jsonPath("$[0].favorite").value(false));

            // The viewer id must arrive as null, not as some default: the
            // favourite subquery is what decides whose hearts light up.
            verify(petService).browse(null, null);
        }

        @Test
        @DisplayName("a signed-in caller's own id reaches the service")
        void signedInCarriesTheViewer() throws Exception {
            when(petService.browse(isNull(), eq(3L))).thenReturn(List.of(pet()));

            mvc.perform(get("/api/v1/pets")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null))))
               .andExpect(status().isOk());

            verify(petService).browse(null, 3L);
        }
    }

    @Nested
    @DisplayName("saving needs an account")
    class Saving {

        @Test
        @DisplayName("an anonymous caller cannot save -- a favourite belongs to a user")
        void anonymousRefused() throws Exception {
            mvc.perform(put("/api/v1/favorites/1")).andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/v1/favorites/1")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/favorites")).andExpect(status().isUnauthorized());

            verifyNoInteractions(favoriteService);
        }

        @Test
        @DisplayName("every signed-in role may keep favourites")
        void anySignedInRole() throws Exception {
            // Not adopter-only, and that is the argument: a favourite is a
            // bookmark against a user id, unlike an application, which is a
            // judgement somebody else makes about you.
            when(favoriteService.mine()).thenReturn(List.of(pet()));

            for (User u : List.of(user(1, Role.ADMIN, null),
                                  user(2, Role.STAFF, 1L),
                                  user(3, Role.ADOPTER, null))) {
                mvc.perform(put("/api/v1/favorites/1")
                           .header("Authorization", "Bearer " + tokenFor(u)))
                   .andExpect(status().isNoContent());
            }

            verify(favoriteService, times(3)).add(1L);
        }

        @Test
        @DisplayName("PUT and DELETE, never a toggle -- a retry must be harmless")
        void putAndDeleteAreIdempotent() throws Exception {
            String token = tokenFor(user(3, Role.ADOPTER, null));

            // A toggle endpoint sends "flip it", which on a flaky connection
            // flips twice and lands where it started. These send the state the
            // person asked for.
            mvc.perform(put("/api/v1/favorites/1").header("Authorization", "Bearer " + token))
               .andExpect(status().isNoContent());
            mvc.perform(put("/api/v1/favorites/1").header("Authorization", "Bearer " + token))
               .andExpect(status().isNoContent());

            verify(favoriteService, times(2)).add(1L);
        }
    }
}
