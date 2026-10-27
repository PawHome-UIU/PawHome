package com.pawhome.backend.quiz;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.quiz.QuizDtos.*;

/**
 * Authoring the question banks.
 *
 * Under /api/v1/admin/**, which SecurityConfig already restricts to ADMIN; the
 * @PreAuthorize is the deliberate second lock, so the rule survives somebody
 * reorganising the URL matchers.
 *
 * NO DELETE MAPPING, and there never will be one. quiz_answers reference both
 * questions and options forever (ON DELETE RESTRICT on each), so a delete
 * would either fail or destroy the evidence behind an approved adoption.
 * Retiring a question is POST /{id}/status with ARCHIVED.
 */
@RestController
@RequestMapping("/api/v1/admin/quiz")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminQuizController {

    private final QuizService service;

    /** One bank, in display order. */
    @GetMapping("/questions")
    public List<QuestionRow> list(@RequestParam QuizType quizType) {
        return service.list(quizType);
    }

    @GetMapping("/questions/{id}")
    public QuestionDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/counts")
    public QuizCounts counts() {
        return service.counts();
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionDetail create(@Valid @RequestBody QuestionRequest request) {
        return service.create(request);
    }

    @PutMapping("/questions/{id}")
    public QuestionDetail update(@PathVariable Long id,
                                 @Valid @RequestBody QuestionRequest request) {
        return service.update(id, request);
    }

    /** Publish, unpublish, or retire. */
    @PostMapping("/questions/{id}/status")
    public QuestionRow setStatus(@PathVariable Long id,
                                 @Valid @RequestBody StatusRequest request) {
        return service.setStatus(id, request.status());
    }

    /** The whole bank's new order. See QuizService.reorder for why it is whole. */
    @PostMapping("/questions/reorder")
    public List<QuestionRow> reorder(@RequestParam QuizType quizType,
                                     @Valid @RequestBody ReorderRequest request) {
        return service.reorder(quizType, request.questionIds());
    }
}
