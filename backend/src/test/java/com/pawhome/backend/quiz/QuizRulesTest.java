package com.pawhome.backend.quiz;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Who may author the question banks, and the one route that must not exist.
 *
 * THE NO-DELETE TEST IS THE POINT OF THIS FILE. quiz_answers reference both
 * questions and options with ON DELETE RESTRICT, so a delete route would
 * either fail at the database or -- if somebody "fixed" that by loosening the
 * constraint to CASCADE -- destroy the answers behind an approved adoption.
 * Archiving is the only retirement, and noDeleteMapping fails the day a
 * DELETE mapping appears on the controller, before any of that can happen.
 *
 * NO DATABASE. The service is mocked; this is about the rules, not the SQL --
 * the SQL was checked separately against a real MySQL. The tokens are real
 * ones from the real JwtService, because faking the authentication would skip
 * exactly what is being tested.
 */
@WebMvcTest(controllers = {AdminQuizController.class, TraitController.class,
                           QuizTakingController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         LoginRateLimitFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-48-bytes-long-for-hs384-ok",
        "app.jwt.expiration-ms=3600000",
        "app.cors.allowed-origins=http://localhost:5174",
})
class QuizRulesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    @MockitoBean UserRepository userRepository;
    @MockitoBean QuizService quizService;
    @MockitoBean QuizTakingService quizTakingService;

    private static final String QUESTION_BODY = """
            {"quizType":"ELIGIBILITY","questionText":"Do you have a yard?",
             "answerType":"YES_NO","category":"ENVIRONMENT","required":true,
             "status":"ACTIVE","options":[{"weight":10},{"weight":0}]}
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

    private static QuizDtos.QuestionRow row() {
        return new QuizDtos.QuestionRow(1L, QuizType.ELIGIBILITY,
                "Do you have a yard?", AnswerType.YES_NO,
                QuestionCategory.ENVIRONMENT, QuestionStatus.ACTIVE,
                true, 1, 2, 0, LocalDateTime.now());
    }

    private static QuizDtos.QuestionDetail detail() {
        return new QuizDtos.QuestionDetail(row(), List.of());
    }

    @BeforeEach
    void noUserByDefault() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------- authoring

    @Nested
    @DisplayName("/api/v1/admin/quiz is ADMIN only")
    class Authoring {

        @Test
        @DisplayName("an admin may read a bank")
        void adminMayList() throws Exception {
            when(quizService.list(QuizType.ELIGIBILITY)).thenReturn(List.of(row()));

            mvc.perform(get("/api/v1/admin/quiz/questions?quizType=ELIGIBILITY")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].answerType").value("YES_NO"));
        }

        @Test
        @DisplayName("an admin may create, and gets 201")
        void adminMayCreate() throws Exception {
            when(quizService.create(any())).thenReturn(detail());

            mvc.perform(post("/api/v1/admin/quiz/questions")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(QUESTION_BODY))
               .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("PRIVILEGE ESCALATION: staff may not author questions")
        void staffMayNotAuthor() throws Exception {
            // The eligibility bank decides who is allowed to adopt. A shelter
            // employee editing the weights would be marking their own homework.
            mvc.perform(post("/api/v1/admin/quiz/questions")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(QUESTION_BODY))
               .andExpect(status().isForbidden());

            mvc.perform(get("/api/v1/admin/quiz/questions?quizType=ELIGIBILITY")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L))))
               .andExpect(status().isForbidden());

            verifyNoInteractions(quizService);
        }

        @Test
        @DisplayName("an adopter may not read the bank -- it is the answer key")
        void adopterMayNotRead() throws Exception {
            // Option weights are exactly what score their own application.
            mvc.perform(get("/api/v1/admin/quiz/questions?quizType=ELIGIBILITY")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null))))
               .andExpect(status().isForbidden());

            verifyNoInteractions(quizService);
        }

        @Test
        @DisplayName("an anonymous caller gets 401")
        void anonymousRefused() throws Exception {
            mvc.perform(get("/api/v1/admin/quiz/questions?quizType=ELIGIBILITY"))
               .andExpect(status().isUnauthorized());

            verifyNoInteractions(quizService);
        }

        @Test
        @DisplayName("a question with no category is a 400 before the service is asked")
        void validationRunsFirst() throws Exception {
            mvc.perform(post("/api/v1/admin/quiz/questions")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"quizType":"ELIGIBILITY","questionText":"",
                             "answerType":"YES_NO","category":null,
                             "required":true,"status":"ACTIVE","options":[]}
                            """))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(quizService);
        }
    }

    // ------------------------------------------------------------- no deleting

    @Nested
    @DisplayName("nothing in a question bank can be deleted")
    class NoDeleting {

        @Test
        @DisplayName("the controller exposes no DELETE mapping at all")
        void noDeleteMapping() throws Exception {
            boolean anyDelete = handlerMapping.getHandlerMethods().entrySet().stream()
                    .filter(e -> e.getValue().getBeanType() == AdminQuizController.class)
                    .map(java.util.Map.Entry::getKey)
                    .map(RequestMappingInfo::getMethodsCondition)
                    .anyMatch(m -> m.getMethods().stream()
                            .anyMatch(rm -> rm.name().equals("DELETE")));

            assertThat(anyDelete)
                    .as("quiz_answers reference questions and options with ON DELETE "
                        + "RESTRICT; archiving is the only way to retire a question")
                    .isFalse();
        }

        @Test
        @DisplayName("archiving goes through the status route, and is ADMIN only")
        void archivingIsAdminOnly() throws Exception {
            when(quizService.setStatus(anyLong(), eq(QuestionStatus.ARCHIVED)))
                    .thenReturn(row());

            mvc.perform(post("/api/v1/admin/quiz/questions/1/status")
                       .header("Authorization", "Bearer " + tokenFor(user(2, Role.STAFF, 1L)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"status\":\"ARCHIVED\"}"))
               .andExpect(status().isForbidden());

            mvc.perform(post("/api/v1/admin/quiz/questions/1/status")
                       .header("Authorization", "Bearer " + tokenFor(user(1, Role.ADMIN, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"status\":\"ARCHIVED\"}"))
               .andExpect(status().isOk());

            verify(quizService, times(1)).setStatus(1L, QuestionStatus.ARCHIVED);
        }
    }

    // ------------------------------------------------------------ the answer key

    @Nested
    @DisplayName("the quiz an applicant sits carries no answer key")
    class TakingIt {

        @Test
        @DisplayName("LEAK: the served options have no weight and no traits")
        void noWeightsInTheTakeProjection() throws Exception {
            // THE TEST THIS FILE EXISTS FOR. An option's weight is what decides
            // whether somebody may adopt. Serving it and hiding it in a React
            // component is a gate anyone opens with devtools, so the projection
            // itself must not be able to express one -- this fails the day
            // somebody "simplifies" the controller by returning OptionRow.
            when(quizTakingService.questions(QuizType.ELIGIBILITY)).thenReturn(
                    List.of(new QuizDtos.TakeQuestionRow(1L, "Have you cared for a pet before?",
                            AnswerType.SINGLE_CHOICE, QuestionCategory.HISTORY, true,
                            List.of(new QuizDtos.TakeOptionRow(11L, "Very experienced", false)))));

            mvc.perform(get("/api/v1/quiz/ELIGIBILITY/questions")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].options[0].label").value("Very experienced"))
               .andExpect(jsonPath("$[0].options[0].weight").doesNotExist())
               .andExpect(jsonPath("$[0].options[0].traitIds").doesNotExist())
               .andExpect(jsonPath("$[0].options[0].inUse").doesNotExist());
        }

        @Test
        @DisplayName("an anonymous caller cannot sit a quiz -- an attempt has a user")
        void anonymousRefused() throws Exception {
            mvc.perform(get("/api/v1/quiz/ELIGIBILITY/questions"))
               .andExpect(status().isUnauthorized());

            verifyNoInteractions(quizTakingService);
        }

        @Test
        @DisplayName("the client never sends a score -- the request has nowhere to put one")
        void scoreIsNotAnInput() throws Exception {
            when(quizTakingService.submit(any(), any())).thenReturn(
                    new QuizDtos.AttemptRow(1L, QuizType.ELIGIBILITY, 72, 100, true,
                            LocalDateTime.now()));

            // "score" here is an unknown property, and it reaching the service
            // would mean AttemptRequest had grown a field it must never have.
            mvc.perform(post("/api/v1/quiz/ELIGIBILITY/attempts")
                       .header("Authorization", "Bearer " + tokenFor(user(3, Role.ADOPTER, null)))
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                            {"score":100,"passed":true,
                             "answers":[{"questionId":1,"optionIds":[11]}]}
                            """))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.score").value(72));
        }
    }

    // ----------------------------------------------------------------- traits

    @Nested
    @DisplayName("GET /api/v1/traits is open to any signed-in role")
    class Traits {

        @Test
        @DisplayName("staff and adopters may read the chips; anonymous may not")
        void anySignedInRole() throws Exception {
            when(quizService.traits()).thenReturn(
                    List.of(new QuizDtos.TraitRow(1L, "Friendly", "friendly")));

            for (User u : List.of(user(1, Role.ADMIN, null),
                                  user(2, Role.STAFF, 1L),
                                  user(3, Role.ADOPTER, null))) {
                mvc.perform(get("/api/v1/traits")
                           .header("Authorization", "Bearer " + tokenFor(u)))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$[0].label").value("Friendly"));
            }

            mvc.perform(get("/api/v1/traits")).andExpect(status().isUnauthorized());
        }
    }
}
