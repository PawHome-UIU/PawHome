package com.pawhome.backend.quiz;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.quiz.QuizDtos.*;

/**
 * Sitting a quiz.
 *
 * AUTHENTICATED, NOT PUBLIC. An attempt is written against a user id, and
 * quiz_attempts.user_id is NOT NULL -- there is no such thing as an anonymous
 * sitting. It is open to every signed-in role rather than adopters alone
 * because staff take the boarding quiz on an owner's behalf and admins need to
 * be able to see what they are publishing.
 *
 * SEPARATE FROM AdminQuizController, and the separation is the security
 * property: everything under /admin/quiz carries option weights, and nothing
 * here does.
 */
@RestController
@RequestMapping("/api/v1/quiz")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class QuizTakingController {

    private final QuizTakingService service;

    /** The published questions of one bank, with no weights. */
    @GetMapping("/{quizType}/questions")
    public List<TakeQuestionRow> questions(@PathVariable QuizType quizType) {
        return service.questions(quizType);
    }

    /**
     * The caller's most recent sitting, or 204.
     *
     * 204 rather than 404: "you have not taken this quiz" is a normal state
     * the apply form asks about every time, not a missing resource.
     */
    @GetMapping("/{quizType}/attempts/latest")
    public ResponseEntity<AttemptRow> latest(@PathVariable QuizType quizType) {
        return service.latest(quizType)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Submit answers. The score is computed here, never sent by the client. */
    @PostMapping("/{quizType}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public AttemptRow submit(@PathVariable QuizType quizType,
                             @Valid @RequestBody AttemptRequest request) {
        return service.submit(quizType, request.answers());
    }
}
