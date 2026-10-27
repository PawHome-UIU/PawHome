package com.pawhome.backend.quiz;

import com.pawhome.backend.security.ScopeGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pawhome.backend.quiz.QuizDtos.*;

/**
 * Sitting a quiz, and scoring it.
 *
 * SEPARATE FROM QuizService BECAUSE THE AUDIENCE IS THE OPPOSITE ONE. That
 * class serves an admin authoring the bank and hands back everything --
 * weights, trait mappings, archived drafts. This one serves the person being
 * assessed, and the first rule is that they must not see the answer key: an
 * option's weight is precisely what decides their eligibility, and shipping it
 * to the browser to be hidden in a component is a gate anyone walks around
 * with devtools. Two services with two projections is how that stays true when
 * somebody later adds a field to the admin DTO.
 *
 * HOW SCORING WORKS, and why max_score is computed rather than stored:
 *
 *   score      the weights of the options actually chosen, summed.
 *   max_score  the BEST available answer to each question asked, summed --
 *              so the denominator is what this attempt could have scored, not
 *              what some other version of the bank could. A question archived
 *              after the attempt cannot retroactively change the mark.
 *   passed     score is at least app.quiz.pass-percent of max_score.
 *
 * PERSONALITY IS NOT SCORED AT ALL. ck_attempts_scored_by_type refuses a
 * personality attempt that carries any of the three, and refuses one of the
 * others that is missing any. The switch below is that constraint in Java.
 */
@Service
@RequiredArgsConstructor
public class QuizTakingService {

    private static final Logger log = LoggerFactory.getLogger(QuizTakingService.class);

    private final QuizQuestionRepository questionRepository;
    private final QuizOptionRepository optionRepository;
    private final QuizAttemptRepository attemptRepository;
    private final QuizAnswerRepository answerRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    /** The bar, as a percentage of what the attempt could have scored. */
    @Value("${app.quiz.pass-percent:60}")
    private int passPercent;

    // -------------------------------------------------------- the questions

    /**
     * The ACTIVE questions of one bank, in order, with no weights.
     *
     * DRAFT and ARCHIVED are excluded here rather than filtered in the caller:
     * a draft is something an admin is still writing, and serving it would put
     * a half-finished question in front of an applicant.
     */
    @Transactional(readOnly = true)
    public List<TakeQuestionRow> questions(QuizType quizType) {
        List<QuizQuestion> asked = questionRepository
                .findByQuizTypeOrderByDisplayOrderAsc(quizType).stream()
                .filter(q -> q.getStatus() == QuestionStatus.ACTIVE)
                .toList();

        if (asked.isEmpty()) {
            // Not an empty list: an empty quiz would be submitted instantly
            // with a score of 0 out of 0, and 0/0 passes or fails depending on
            // which way the division falls. Refusing is the honest answer.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That quiz has no published questions yet.");
        }

        Map<Long, List<QuizOption>> byQuestion = optionsFor(asked);

        return asked.stream().map(q -> new TakeQuestionRow(
                q.getId(), q.getQuestionText(), q.getAnswerType(), q.getCategory(),
                q.isRequired(),
                byQuestion.getOrDefault(q.getId(), List.of()).stream()
                        .map(o -> new TakeOptionRow(o.getId(), o.getLabel(), o.isRequiresText()))
                        .toList()))
                .toList();
    }

    /**
     * The traits the caller's latest personality attempt produced.
     *
     * READ FROM v_adopter_traits, which is where the definition lives: it picks
     * the newest PERSONALITY attempt per user and collects the traits of every
     * option chosen in it. Re-deriving that here would be a second copy of the
     * rule, free to disagree with the one the match score already uses -- and a
     * recommendations page whose trait list and whose percentages disagreed
     * would be worse than one with neither.
     *
     * Empty when the quiz has never been sat, which is a normal state and not
     * an error: the page asks about it on every visit.
     */
    @Transactional(readOnly = true)
    public List<String> myTraits() {
        return jdbc.queryForList(
                "SELECT trait_label FROM v_adopter_traits WHERE user_id = ? ORDER BY trait_label",
                String.class, scopeGuard.require().id());
    }

    /** The caller's most recent attempt at a quiz, if they have sat it. */
    @Transactional(readOnly = true)
    public Optional<AttemptRow> latest(QuizType quizType) {
        return attemptRepository
                .findFirstByUserIdAndQuizTypeOrderByIdDesc(scopeGuard.require().id(), quizType)
                .map(QuizTakingService::toRow);
    }

    // ------------------------------------------------------------ submitting

    /**
     * Record a sitting.
     *
     * EVERY ANSWER IS RE-READ FROM THE DATABASE. The request carries question
     * and option ids and nothing else that matters -- no weights, no score.
     * The alternative, trusting a score the browser calculated, is the same
     * thing as letting applicants mark their own eligibility.
     */
    @Transactional
    public AttemptRow submit(QuizType quizType, List<AnswerRequest> answers) {
        Long userId = scopeGuard.require().id();

        List<QuizQuestion> asked = questionRepository
                .findByQuizTypeOrderByDisplayOrderAsc(quizType).stream()
                .filter(q -> q.getStatus() == QuestionStatus.ACTIVE)
                .toList();
        if (asked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That quiz has no published questions yet.");
        }

        Map<Long, QuizQuestion> questionById = asked.stream()
                .collect(Collectors.toMap(QuizQuestion::getId, Function.identity()));
        Map<Long, List<QuizOption>> optionsByQuestion = optionsFor(asked);
        Map<Long, QuizOption> optionById = optionsByQuestion.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(QuizOption::getId, Function.identity()));

        Map<Long, AnswerRequest> given = new LinkedHashMap<>();
        for (AnswerRequest a : answers) {
            if (!questionById.containsKey(a.questionId())) {
                // Either a question from another quiz or one archived since the
                // form was loaded. Both mean this attempt is not the quiz that
                // is currently being asked.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That answer is for a question this quiz is not asking. "
                        + "Reload and start again.");
            }
            if (given.put(a.questionId(), a) != null) {
                // uq_answer would catch a repeated (question, option) pair, but
                // not two different answers to the same single-choice question.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The same question was answered twice.");
            }
        }

        // Every required question, before anything is written.
        for (QuizQuestion q : asked) {
            if (!q.isRequired()) continue;
            AnswerRequest a = given.get(q.getId());
            boolean answered = a != null && (
                    (a.optionIds() != null && !a.optionIds().isEmpty()) || a.scaleValue() != null);
            if (!answered) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This question still needs an answer: " + q.getQuestionText());
            }
        }

        int score = 0;
        int maxScore = 0;
        List<QuizAnswer> rows = new ArrayList<>();

        for (QuizQuestion q : asked) {
            List<QuizOption> options = optionsByQuestion.getOrDefault(q.getId(), List.of());
            AnswerRequest a = given.get(q.getId());

            // The best this question could have been worth, whether or not it
            // was answered. A skipped optional question still counts against
            // the denominator -- leaving it out would make skipping a way to
            // raise your own percentage.
            maxScore += options.stream().mapToInt(QuizOption::getWeight).max().orElse(0);

            if (a == null) continue;

            if (q.getAnswerType() == AnswerType.SCALE) {
                if (a.scaleValue() == null) continue;
                rows.add(QuizAnswer.builder()
                        .questionId(q.getId())
                        .scaleValue(a.scaleValue().shortValue())
                        .answerText(blankToNull(a.answerText()))
                        .build());
                continue;
            }

            List<Long> chosen = a.optionIds() == null ? List.of() : a.optionIds();
            if (chosen.size() > 1 && q.getAnswerType() != AnswerType.MULTI_CHOICE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only one answer is allowed for: " + q.getQuestionText());
            }

            for (Long optionId : new HashSet<>(chosen)) {
                QuizOption option = optionById.get(optionId);
                if (option == null || !option.getQuestionId().equals(q.getId())) {
                    // An option id from a different question would otherwise
                    // add ITS weight to this question's score.
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "That answer does not belong to the question it was given for.");
                }
                score += option.getWeight();
                rows.add(QuizAnswer.builder()
                        .questionId(q.getId())
                        .optionId(optionId)
                        .answerText(option.isRequiresText() ? blankToNull(a.answerText()) : null)
                        .build());
            }
        }

        boolean scored = quizType != QuizType.PERSONALITY;

        // BOTH COLUMNS ARE SMALLINT. The sum is accumulated as an int because
        // that is what arithmetic on shorts produces anyway; casting a total
        // above 32767 down would wrap it NEGATIVE, and the row would then fail
        // ck_attempts_score (score >= 0) with an error naming a constraint
        // rather than the bank that caused it. Reaching this needs weights
        // adding to more than 32767, which is an authoring mistake -- so it is
        // reported as one.
        if (scored && maxScore > Short.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This quiz's option weights add up to more than a score can "
                    + "hold (" + maxScore + " of a possible " + Short.MAX_VALUE
                    + "). Lower them and try again.");
        }

        // ck_attempts_score is 0 <= score <= max_score. A bank whose weights
        // are all zero gives 0/0, which is not a failure -- it is a quiz nobody
        // has put weights on yet, and dividing by it below would throw.
        QuizAttempt attempt = attemptRepository.save(QuizAttempt.builder()
                .userId(userId)
                .quizType(quizType)
                .score(scored ? Short.valueOf((short) score) : null)
                .maxScore(scored ? Short.valueOf((short) maxScore) : null)
                .passed(scored ? (maxScore == 0 || score * 100 >= maxScore * passPercent) : null)
                .build());

        rows.forEach(r -> r.setAttemptId(attempt.getId()));
        answerRepository.saveAll(rows);

        log.info("User {} completed the {} quiz as attempt {} ({})",
                userId, quizType, attempt.getId(),
                scored ? score + "/" + maxScore : "unscored");

        return toRow(attempt);
    }

    // ------------------------------------------------------------ internals

    /** Every option of every question asked, in one query rather than per row. */
    private Map<Long, List<QuizOption>> optionsFor(List<QuizQuestion> asked) {
        List<Long> ids = asked.stream().map(QuizQuestion::getId).toList();
        return optionRepository.findByQuestionIdInOrderByDisplayOrderAsc(ids).stream()
                .collect(Collectors.groupingBy(QuizOption::getQuestionId));
    }

    /** Widened to int at the boundary: a caller should not care how wide the column is. */
    private static AttemptRow toRow(QuizAttempt a) {
        return new AttemptRow(a.getId(), a.getQuizType(),
                a.getScore() == null ? null : a.getScore().intValue(),
                a.getMaxScore() == null ? null : a.getMaxScore().intValue(),
                a.getPassed(), a.getSubmittedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
