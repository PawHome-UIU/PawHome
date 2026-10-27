package com.pawhome.backend.quiz;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.pawhome.backend.quiz.QuizDtos.TraitRow;

/**
 * The temperament chips.
 *
 * Its own controller rather than a method on AdminQuizController because the
 * list is not an admin secret: the pet editor and the public browse filters
 * need the same chips, and the day they do, this route already exists and does
 * not have to be moved out from under /admin.
 *
 * Authenticated for now -- staff tag pets with these, admins map options to
 * them -- and readable by any signed-in role. There is nothing in a trait
 * label worth hiding, but there is also no anonymous caller who needs one yet.
 */
@RestController
@RequestMapping("/api/v1/traits")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class TraitController {

    private final QuizService service;
    private final QuizTakingService takingService;

    @GetMapping
    public List<TraitRow> list() {
        return service.traits();
    }

    /**
     * The caller's OWN traits, from their latest personality attempt.
     *
     * Here rather than under /quiz because it is a fact about the person, not
     * about a quiz: the recommendations page shows it beside a set of match
     * percentages, and both come from the same view. An empty list means the
     * quiz has not been sat -- a normal answer, not a 404.
     */
    @GetMapping("/mine")
    public List<String> mine() {
        return takingService.myTraits();
    }
}
