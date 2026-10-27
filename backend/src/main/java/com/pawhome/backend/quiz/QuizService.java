package com.pawhome.backend.quiz;

import com.pawhome.backend.security.ScopeGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pawhome.backend.quiz.QuizDtos.*;

/**
 * Authoring the three question banks.
 *
 * Four rules drive almost every decision in this file, and three of them are
 * database facts rather than opinions:
 *
 * 1. AN ANSWERED OPTION CANNOT BE DELETED. fk_answers_option is ON DELETE
 *    RESTRICT, so the obvious edit strategy -- wipe the question's options and
 *    write the new set -- fails the moment anybody has taken the quiz. Options
 *    are therefore matched by id and updated in place; only ones nobody has
 *    chosen may be removed, and trying to remove the rest is refused with a
 *    message naming the option rather than a constraint dump.
 *
 * 2. A QUESTION IS NEVER DELETED EITHER, for the same reason one step up
 *    (fk_answers_question). ARCHIVED is how a question stops being asked. There
 *    is no delete method here at all.
 *
 * 3. A SCALE QUESTION HAS NO OPTIONS. ck_answers_scale pins scale_value to
 *    1..5 and quiz_answers.option_id is nullable, so the schema's design is a
 *    bare number with no option row behind it. Writing five "1".."5" options
 *    would be a second, disagreeing definition of the same scale.
 *
 * 4. WHOSE LABELS WIN DEPENDS ON THE TYPE. For SINGLE_CHOICE and MULTI_CHOICE
 *    the author writes them. For YES_NO the service does -- a yes/no question
 *    whose choices are "Yes" and "Maybe" is not a yes/no question -- while the
 *    author still supplies each branch's weight and traits.
 */
@Service
@RequiredArgsConstructor
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);

    private final QuizQuestionRepository questionRepository;
    private final QuizOptionRepository optionRepository;
    private final TraitRepository traitRepository;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    // ------------------------------------------------------------- reading

    /**
     * Every question in one bank, in order.
     *
     * The two counts are correlated subqueries in a single statement. Done as
     * a loop it is fourteen questions and twenty-nine queries, and the shape
     * that turns into hundreds once a bank grows.
     */
    @Transactional(readOnly = true)
    public List<QuestionRow> list(QuizType quizType) {
        return jdbc.query("""
            SELECT  q.id, q.quiz_type, q.question_text, q.answer_type, q.category,
                    q.status, q.is_required, q.display_order, q.created_at,
                    (SELECT COUNT(*) FROM quiz_options o
                      WHERE o.question_id = q.id)                    AS option_count,
                    (SELECT COUNT(*) FROM quiz_answers a
                      WHERE a.question_id = q.id)                    AS answered_count
            FROM        quiz_questions q
            WHERE       q.quiz_type = ?
            ORDER BY    q.display_order, q.id
            """, (rs, i) -> new QuestionRow(
                    rs.getLong("id"),
                    QuizType.valueOf(rs.getString("quiz_type")),
                    rs.getString("question_text"),
                    AnswerType.valueOf(rs.getString("answer_type")),
                    QuestionCategory.valueOf(rs.getString("category")),
                    QuestionStatus.valueOf(rs.getString("status")),
                    rs.getBoolean("is_required"),
                    rs.getInt("display_order"),
                    rs.getInt("option_count"),
                    rs.getLong("answered_count"),
                    rs.getTimestamp("created_at").toLocalDateTime()),
                quizType.name());
    }

    /** One question with its options, for the editor. */
    @Transactional(readOnly = true)
    public QuestionDetail get(Long id) {
        QuestionRow row = list(load(id).getQuizType()).stream()
                .filter(q -> q.id().equals(id)).findFirst()
                .orElseThrow(() -> notFound(id));
        return new QuestionDetail(row, options(id));
    }

    @Transactional(readOnly = true)
    public List<TraitRow> traits() {
        return traitRepository.findByActiveTrueOrderByLabelAsc().stream()
                .map(t -> new TraitRow(t.getId(), t.getLabel(), t.getSlug()))
                .toList();
    }

    @Transactional(readOnly = true)
    public QuizCounts counts() {
        return jdbc.queryForObject("""
            SELECT  COUNT(*)                             AS total,
                    SUM(quiz_type = 'ELIGIBILITY')       AS eligibility,
                    SUM(quiz_type = 'PERSONALITY')       AS personality,
                    SUM(quiz_type = 'BOARDING')          AS boarding,
                    SUM(status    = 'ACTIVE')            AS active
            FROM quiz_questions
            """, (rs, i) -> new QuizCounts(
                    rs.getLong("total"), rs.getLong("eligibility"),
                    rs.getLong("personality"), rs.getLong("boarding"),
                    rs.getLong("active")));
    }

    // ------------------------------------------------------------- writing

    @Transactional
    public QuestionDetail create(QuestionRequest r) {
        validate(r, List.of());

        // Appended to the end of its own bank. Asking the author to pick a
        // position while writing the question is a decision in the wrong
        // place; reorder is its own action.
        int nextOrder = questionRepository
                .findByQuizTypeOrderByDisplayOrderAsc(r.quizType()).stream()
                .mapToInt(QuizQuestion::getDisplayOrder).max().orElse(0) + 1;

        QuizQuestion saved = questionRepository.save(QuizQuestion.builder()
                .quizType(r.quizType())
                .questionText(r.questionText().trim())
                .answerType(r.answerType())
                .category(r.category())
                .status(r.status())
                .required(r.required())
                .displayOrder(nextOrder)
                .createdBy(scopeGuard.require().id())
                .build());

        writeOptions(saved, r, List.of());
        log.info("Admin {} created {} question {}",
                scopeGuard.require().id(), r.quizType(), saved.getId());
        return get(saved.getId());
    }

    @Transactional
    public QuestionDetail update(Long id, QuestionRequest r) {
        QuizQuestion question = load(id);
        List<OptionRow> existing = options(id);
        validate(r, existing);

        // The quiz a question belongs to is not editable. Moving one between
        // banks would carry its answers with it into a quiz that never asked
        // it, and V6 pins an application's attempt to a quiz_type precisely so
        // that cannot drift.
        if (r.quizType() != question.getQuizType()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A question cannot be moved to a different quiz. "
                    + "Archive it and write a new one.");
        }

        question.setQuestionText(r.questionText().trim());
        question.setAnswerType(r.answerType());
        question.setCategory(r.category());
        question.setStatus(r.status());
        question.setRequired(r.required());
        questionRepository.save(question);

        writeOptions(question, r, existing);
        log.info("Admin {} edited question {}", scopeGuard.require().id(), id);
        return get(id);
    }

    /**
     * Publish, unpublish or retire.
     *
     * Idempotent, and there is no guard against archiving the last ACTIVE
     * question in a bank. That would be a rule about whether a quiz may be
     * empty, which belongs where a quiz is SERVED -- refusing it here would
     * leave an admin unable to retire a question they know is wrong.
     */
    @Transactional
    public QuestionRow setStatus(Long id, QuestionStatus status) {
        QuizQuestion question = load(id);
        if (question.getStatus() != status) {
            question.setStatus(status);
            questionRepository.save(question);
            log.info("Admin {} set question {} to {}",
                    scopeGuard.require().id(), id, status);
        }
        return list(question.getQuizType()).stream()
                .filter(q -> q.id().equals(id)).findFirst().orElseThrow(() -> notFound(id));
    }

    /**
     * Reorder a whole bank at once.
     *
     * The client sends every id in the bank, not a "move this one up". A
     * relative move needs the server to know which list the client was looking
     * at, and two admins reordering at the same time then interleave into an
     * order neither of them chose. Sending the whole list means the last write
     * wins, visibly.
     */
    @Transactional
    public List<QuestionRow> reorder(QuizType quizType, List<Long> ids) {
        List<QuizQuestion> bank = questionRepository
                .findByQuizTypeOrderByDisplayOrderAsc(quizType);

        Set<Long> sent = new HashSet<>(ids);
        if (sent.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The same question appears twice in that order.");
        }
        if (sent.size() != bank.size()
                || !bank.stream().allMatch(q -> sent.contains(q.getId()))) {
            // A partial list would silently leave the missing questions
            // wherever they were, interleaved through the new order.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That order does not list every question in this quiz. "
                    + "Reload the page and try again.");
        }

        Map<Long, QuizQuestion> byId = bank.stream()
                .collect(Collectors.toMap(QuizQuestion::getId, q -> q));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setDisplayOrder(i + 1);
        }
        questionRepository.saveAll(bank);
        return list(quizType);
    }

    // ------------------------------------------------------------- internals

    /**
     * Write the option set, keeping every option anybody has answered.
     *
     * THE TWO-PASS RENUMBER IS NOT DEFENSIVE, IT IS REQUIRED. uq_option_order
     * is UNIQUE (question_id, display_order): swapping options 1 and 2 sets one
     * of them to a number the other still holds, and the statement fails
     * mid-flight. Parking the survivors on negative orders first empties the
     * whole positive range before anything claims a place in it. Nothing else
     * writes negatives, so the parking spots are free by construction.
     */
    private void writeOptions(QuizQuestion question, QuestionRequest r,
                              List<OptionRow> existing) {

        List<OptionRequest> wanted = optionsFor(r);

        Map<Long, OptionRow> existingById = existing.stream()
                .collect(Collectors.toMap(OptionRow::id, o -> o));
        Set<Long> kept = wanted.stream().map(OptionRequest::id)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        // Anything dropped. An answered one cannot go -- say which.
        for (OptionRow gone : existing) {
            if (kept.contains(gone.id())) continue;
            if (gone.inUse()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "\"" + gone.label() + "\" has already been chosen by "
                        + "somebody taking this quiz, so it cannot be removed. "
                        + "Reword it instead, or archive the question.");
            }
            optionRepository.deleteById(gone.id());
        }

        // Pass one: park every survivor out of the positive range.
        int park = -1;
        for (OptionRequest o : wanted) {
            if (o.id() == null || !existingById.containsKey(o.id())) continue;
            jdbc.update("UPDATE quiz_options SET display_order = ? WHERE id = ?",
                    park--, o.id());
        }

        // Pass two: final positions, and the values that go with them.
        List<Long> optionIds = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            OptionRequest o = wanted.get(i);
            short weight = r.quizType() == QuizType.PERSONALITY ? 0 : o.weight();

            if (o.id() != null && existingById.containsKey(o.id())) {
                QuizOption row = optionRepository.findById(o.id()).orElseThrow();
                row.setLabel(o.label().trim());
                row.setWeight(weight);
                row.setRequiresText(o.requiresText());
                row.setDisplayOrder(i + 1);
                optionRepository.save(row);
                optionIds.add(row.getId());
            } else {
                QuizOption row = optionRepository.save(QuizOption.builder()
                        .questionId(question.getId())
                        .label(o.label().trim())
                        .weight(weight)
                        .requiresText(o.requiresText())
                        .displayOrder(i + 1)
                        .build());
                optionIds.add(row.getId());
            }
        }

        writeTraits(question, wanted, optionIds);
    }

    /**
     * Rewrite option_traits for this question.
     *
     * Only PERSONALITY options carry traits. On the other two banks the
     * mappings are cleared rather than ignored: option_traits feeds nothing but
     * the match score, and a stale row there would quietly steer a
     * recommendation from a quiz that has no business steering one.
     */
    private void writeTraits(QuizQuestion question, List<OptionRequest> wanted,
                             List<Long> optionIds) {
        if (optionIds.isEmpty()) return;

        String placeholders = optionIds.stream().map(x -> "?")
                .collect(Collectors.joining(","));
        jdbc.update("DELETE FROM option_traits WHERE option_id IN (" + placeholders + ")",
                optionIds.toArray());

        if (question.getQuizType() != QuizType.PERSONALITY) return;

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            List<Long> traitIds = wanted.get(i).traitIds();
            if (traitIds == null) continue;
            for (Long traitId : new HashSet<>(traitIds)) {
                rows.add(new Object[]{optionIds.get(i), traitId});
            }
        }
        if (!rows.isEmpty()) {
            // One batch. The alternative is a round trip per chip, and a
            // four-option question with three chips each is twelve of them.
            jdbc.batchUpdate("INSERT INTO option_traits (option_id, trait_id) VALUES (?, ?)", rows);
        }
    }

    /**
     * The option set the type demands, with labels the type owns.
     *
     * SCALE gets none at all (see the class comment). YES_NO gets exactly two,
     * labelled here, keeping whatever weights and traits the author put on each
     * branch. Everything else is the author's list, untouched.
     */
    private static List<OptionRequest> optionsFor(QuestionRequest r) {
        List<OptionRequest> given = r.options() == null ? List.of() : r.options();

        return switch (r.answerType()) {
            case SCALE -> List.of();
            case YES_NO -> {
                List<OptionRequest> two = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    OptionRequest src = i < given.size() ? given.get(i) : null;
                    two.add(new OptionRequest(
                            src == null ? null : src.id(),
                            i == 0 ? "Yes" : "No",
                            src == null ? 0 : src.weight(),
                            false,                       // no free-text on yes/no
                            src == null ? List.of() : src.traitIds()));
                }
                yield two;
            }
            case SINGLE_CHOICE, MULTI_CHOICE -> given.stream()
                    .filter(o -> o.label() != null && !o.label().isBlank())
                    .toList();
        };
    }

    /** Everything that must be true before a question is written. */
    private void validate(QuestionRequest r, List<OptionRow> existing) {
        if (r.status() == QuestionStatus.ARCHIVED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Archive a question from its row, not from the editor.");
        }

        List<OptionRequest> options = optionsFor(r);

        if (r.answerType() == AnswerType.SCALE) {
            // Not a silent ignore: an author who filled in five options and is
            // told nothing would reasonably believe they were saved.
            if (r.options() != null && !r.options().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A scale question has no answer options -- it records a "
                        + "number from 1 to 5. Remove the options or pick a "
                        + "different answer type.");
            }
            // Everything an existing question had must therefore go, and an
            // answered option cannot. Caught here so the message explains it.
            if (existing.stream().anyMatch(OptionRow::inUse)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This question has already been answered, so it cannot "
                        + "be turned into a scale question. Archive it and "
                        + "write a new one.");
            }
            return;
        }

        if (r.answerType().isAuthored() && options.size() < 2) {
            // One option is not a choice, it is a statement.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A question needs at least two answer options.");
        }

        List<String> labels = options.stream()
                .map(o -> o.label().trim().toLowerCase()).toList();
        if (new HashSet<>(labels).size() != labels.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Two options have the same label -- scoring could not tell "
                    + "them apart.");
        }

        if (r.quizType() == QuizType.PERSONALITY) {
            // A personality option that maps to nothing is inert: it cannot
            // move a match score in either direction, so the quiz silently
            // stops working. Refusing it here is the only place that shows.
            boolean bare = options.stream()
                    .anyMatch(o -> o.traitIds() == null || o.traitIds().isEmpty());
            if (bare) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Every personality option needs at least one trait -- "
                        + "an option with none can never affect a match score.");
            }
            Set<Long> known = traitRepository.findAll().stream()
                    .map(Trait::getId).collect(Collectors.toSet());
            boolean unknown = options.stream()
                    .flatMap(o -> o.traitIds().stream())
                    .anyMatch(t -> !known.contains(t));
            if (unknown) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "One of those traits no longer exists. Reload and try again.");
            }
        }
    }

    private List<OptionRow> options(Long questionId) {
        Map<Long, List<Long>> traits = new HashMap<>();
        jdbc.query("""
                SELECT ot.option_id, ot.trait_id
                  FROM option_traits ot
                  JOIN quiz_options o ON o.id = ot.option_id
                 WHERE o.question_id = ?
                """, rs -> {
            traits.computeIfAbsent(rs.getLong("option_id"), k -> new ArrayList<>())
                  .add(rs.getLong("trait_id"));
        }, questionId);

        Set<Long> answered = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT a.option_id
                  FROM quiz_answers a
                  JOIN quiz_options o ON o.id = a.option_id
                 WHERE o.question_id = ?
                """, Long.class, questionId));

        return optionRepository.findByQuestionIdOrderByDisplayOrderAsc(questionId).stream()
                .map(o -> new OptionRow(o.getId(), o.getLabel(), o.getWeight(),
                        o.isRequiresText(), o.getDisplayOrder(),
                        traits.getOrDefault(o.getId(), List.of()),
                        answered.contains(o.getId())))
                .toList();
    }

    private QuizQuestion load(Long id) {
        return questionRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question.");
    }
}
