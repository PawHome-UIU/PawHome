import { useEffect, useState } from "react";
import { FiArrowRight, FiCheck } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The eligibility quiz, as a modal wizard.
 *
 * Two things the design fixes that a naive form gets wrong:
 *  - one question per screen, so a five-question quiz never looks like a form;
 *  - MULTI_CHOICE questions really do accept several answers, which is why the
 *    answer state is an array of option ids rather than one value.
 *
 * Q4 is the multi-select, confirmed by the design showing "Dogs" and "Young
 * kids" ticked at once -- a combination that is perfectly possible, unlike the
 * contradictory pairs in the boarding frames.
 *
 * NO FREE-TEXT FOLLOW-UPS HERE, deliberately. An earlier version put one behind
 * "Other" and "Other pets"; the design has none on any of the five eligibility
 * frames, while the boarding frames show follow-ups explicitly wherever they
 * are wanted. That contrast reads as intent, not omission -- the eligibility
 * quiz is scored, and a free-text answer cannot be.
 *
 * Scoring lives on the server. Sending the chosen option ids and letting the
 * backend sum their weights is the only version that cannot be tampered with
 * from the console.
 */
/**
 * WHY THE ANSWER TYPES ARE READ FROM THE SERVER and not assumed here. A
 * hard-coded bank lived in this file: five questions with invented option ids
 * that matched nothing in quiz_options, so the ids it submitted could never
 * have been scored. The bank is a table an admin edits, and the moment they
 * add a sixth question this component has to ask it.
 *
 * THE "NONE CLEARS THE OTHERS" BEHAVIOUR WENT WITH IT. It was a nice touch and
 * it was a lie: nothing in the schema marks an option as exclusive, so the
 * rule only existed for one hard-coded id. Re-adding it means a column on
 * quiz_options and an admin control to set it, not a special case here.
 *
 * SCORING STAYS ON THE SERVER. This sends chosen option ids; the weights that
 * turn them into a mark are never sent to the browser at all -- see
 * QuizDtos.TakeOptionRow.
 */
/**
 * What each bank is called, and what sitting it is for.
 *
 * The title used to be the literal string "Eligibility Quiz" while the
 * component already accepted a quizType -- so the personality quiz opened
 * under the wrong heading, which on a page about personality matching is not a
 * cosmetic slip.
 */
const QUIZZES = {
  ELIGIBILITY: {
    title: "Eligibility Quiz",
    footer: "Your answers are scored on the server; this page never sees the marks.",
  },
  PERSONALITY: {
    title: "Personality Quiz",
    // Not scored at all -- ck_attempts_scored_by_type refuses a mark on a
    // personality attempt. Saying "scored" here would be a lie about what
    // happens next.
    footer: "Your answers become a set of traits, which is what the match scores compare against.",
  },
  BOARDING: {
    title: "Boarding Quiz",
    footer: "Your answers help the shelter prepare for your pet's stay.",
  },
};

export default function EligibilityQuizModal({ open, onClose, onComplete, quizType = "ELIGIBILITY" }) {
  const quiz = QUIZZES[quizType] ?? QUIZZES.ELIGIBILITY;
  const [questions, setQuestions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [step, setStep] = useState(0);
  const [answers, setAnswers] = useState({});
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState("");

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/quiz/${quizType}/questions`);
        if (!cancelled) setQuestions(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the quiz."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [quizType]);

  const q = questions[step];
  const picked = q ? (answers[q.id] ?? []) : [];
  const last = step === questions.length - 1;

  const multi = q?.answerType === "MULTI_CHOICE";

  function choose(optId) {
    setAnswers((a) => {
      const cur = a[q.id] ?? [];
      if (!multi) return { ...a, [q.id]: [optId] };
      return {
        ...a,
        [q.id]: cur.includes(optId) ? cur.filter((x) => x !== optId) : [...cur, optId],
      };
    });
  }

  // An optional question may be skipped; a required one may not. The server
  // checks the same thing, so this is only about not making somebody submit to
  // find out.
  const canAdvance = !q || picked.length > 0 || !q.required;

  async function next() {
    if (!last) { setStep((s) => s + 1); return; }

    setBusy(true);
    setSubmitError("");
    try {
      const { data } = await api.post(`/quiz/${quizType}/attempts`, {
        answers: questions.map((x) => ({
          questionId: x.id,
          optionIds: answers[x.id] ?? [],
          scaleValue: null,
          answerText: null,
        })).filter((x) => x.optionIds.length > 0),
      });
      // The ATTEMPT is what the caller needs -- an application is filed
      // against its id, not against the answers.
      onComplete?.(data);
      onClose();
    } catch (err) {
      setSubmitError(apiError(err, "That attempt could not be recorded."));
    } finally {
      setBusy(false);
    }
  }

  function close() {
    if (busy) return;
    onClose();
  }

  if (loading || loadError || questions.length === 0) {
    return (
      <Modal open={open} onClose={close} title={quiz.title} size="sm">
        <p className={`py-8 text-center text-[15px] ${loadError ? "text-pw-danger" : "text-pw-muted"}`}>
          {loadError || (loading ? "Loading the quiz…" : "This quiz has no published questions yet.")}
        </p>
        <Button full size="lg" variant="outline" onClick={close}>Close</Button>
      </Modal>
    );
  }

  return (
    <Modal open={open} onClose={close} title={quiz.title} size="sm">
      <div className="text-center">
        <div className="flex justify-center gap-2" aria-hidden="true">
          {questions.map((_, i) => (
            <span key={i} className={`h-2.5 w-2.5 rounded-full ${i === step ? "bg-pw-button" : i < step ? "bg-pw-stroke" : "bg-pw-input"}`} />
          ))}
        </div>
        <p className="mt-2.5 text-sm text-pw-muted">Question {step + 1} of {questions.length}</p>
        <h3 className="mt-4 text-xl font-semibold leading-snug">{q.questionText}</h3>
        {!q.required && <p className="mt-1.5 text-sm text-pw-muted">Optional</p>}
      </div>

      <div className="mt-6 space-y-3" role={multi ? "group" : "radiogroup"} aria-label={q.questionText}>
        {q.options.map((o) => {
          const on = picked.includes(o.id);
          return (
            <button key={o.id} type="button" onClick={() => choose(o.id)}
              role={multi ? "checkbox" : "radio"} aria-checked={on}
              className={[
                "flex w-full items-center justify-between gap-3 rounded-pill border px-5 py-3.5 text-left transition-colors",
                on ? "border-pw-button bg-pw-input" : "border-pw-stroke/60 bg-white hover:border-pw-stroke",
              ].join(" ")}>
              <span className={`text-[15px] ${on ? "font-medium text-pw-button" : "text-pw-ink"}`}>
                {o.label}
              </span>
              <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border ${
                on ? "border-pw-button bg-pw-button text-white" : "border-pw-stroke"}`}>
                {on && <FiCheck className="h-3 w-3" />}
              </span>
            </button>
          );
        })}
      </div>

      {submitError && (
        <p role="alert" className="mt-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
          {submitError}
        </p>
      )}

      <div className="mt-7 flex gap-3">
        {step > 0 && (
          <Button variant="outline" size="lg" disabled={busy} onClick={() => setStep((s) => s - 1)}>
            Back
          </Button>
        )}
        <Button full size="lg" disabled={!canAdvance || busy} onClick={next}>
          {last ? (busy ? "Submitting…" : "Submit")
                : <>Next <FiArrowRight className="h-4 w-4" /></>}
        </Button>
      </div>
      <p className="mt-3 text-center text-xs text-pw-muted">{quiz.footer}</p>
    </Modal>
  );
}
