import { useEffect, useState } from "react";
import { FiPlus, FiTrash2, FiLock, FiInfo } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { Select, Textarea, Segmented } from "../ui/Field";
import api from "../../api/client";

/**
 * The editor for quiz_questions, quiz_options and option_traits.
 *
 * WHAT THIS FORM GETS WRONG IF NOBODY WATCHES IT, and what the shape below is
 * defending against:
 *
 * QUIZ TYPE AND CATEGORY ARE DIFFERENT COLUMNS. The first version offered
 * "Eligibility / Personality" under the heading "Category" and never wrote
 * quiz_questions.category at all -- a NOT NULL column. They are two axes: an
 * eligibility question about somebody's home is quiz_type ELIGIBILITY,
 * category ENVIRONMENT.
 *
 * THE ANSWER TYPE DECIDES WHO OWNS THE LABELS.
 *   SINGLE_CHOICE / MULTI_CHOICE  the author writes them.
 *   YES_NO                        fixed to Yes and No. A yes/no question whose
 *                                 choices are "Yes" and "Maybe" is not one.
 *                                 The weights are still the author's.
 *   SCALE                         no options at all. ck_answers_scale pins the
 *                                 answer to a number from 1 to 5, so an option
 *                                 row would be a second definition of the same
 *                                 scale, free to disagree with the first.
 *
 * AN ANSWERED OPTION CANNOT BE DELETED. fk_answers_option is ON DELETE
 * RESTRICT. The server refuses, so the bin button is disabled here rather than
 * letting somebody discover it at the end of a long edit.
 *
 * A PERSONALITY OPTION WITH NO TRAITS IS INERT. It cannot move a match score in
 * either direction, so the quiz silently stops working -- which is why the
 * trait picker is required on that branch rather than optional.
 */
const CATEGORIES = [
  ["ENVIRONMENT", "Environment — the home, other animals, children"],
  ["LIFESTYLE", "Lifestyle — hours, activity, routine"],
  ["HISTORY", "History — previous pets and adoptions"],
  ["COMMITMENT", "Commitment — cost, time, the long term"],
  ["HEALTH", "Health — medical facts"],
];

const ANSWER_TYPES = [
  ["SINGLE_CHOICE", "Single choice — one of many"],
  ["MULTI_CHOICE", "Multiple choice — any of many"],
  ["YES_NO", "Yes / No"],
  ["SCALE", "Scale — a number from 1 to 5"],
];

let nextOptionId = -1; // negative ids mark rows that do not exist server-side yet
const newOption = () => ({ id: nextOptionId--, label: "", weight: 0, traitIds: [], inUse: false });

/** Yes/No always shows exactly two rows; the labels are not the author's. */
const yesNoRows = (from = []) => [0, 1].map((i) => ({
  ...(from[i] ?? newOption()),
  label: i === 0 ? "Yes" : "No",
}));

export default function AddQuestionModal({
  open, question = null, onClose, onSave, busy = false, error = "",
}) {
  const editing = question != null;

  const [quizType, setQuizType] = useState(question?.question.quizType ?? "ELIGIBILITY");
  const [text, setText] = useState(question?.question.questionText ?? "");
  const [category, setCategory] = useState(question?.question.category ?? "ENVIRONMENT");
  const [answerType, setAnswerType] = useState(question?.question.answerType ?? "SINGLE_CHOICE");
  const [status, setStatus] = useState(question?.question.status ?? "ACTIVE");
  const [required, setRequired] = useState(question?.question.required ?? true);
  const [options, setOptions] = useState(() => {
    const from = question?.options?.map((o) => ({
      id: o.id, label: o.label, weight: o.weight,
      traitIds: o.traitIds ?? [], inUse: o.inUse,
    }));
    if (!from || from.length === 0) return [newOption(), newOption()];
    return from;
  });
  const [errors, setErrors] = useState({});

  // The real traits table, not a hard-coded array. A picker offering chips
  // that do not exist writes option_traits rows that fail the foreign key.
  const [traits, setTraits] = useState([]);
  useEffect(() => {
    let cancelled = false;
    api.get("/traits")
      .then(({ data }) => { if (!cancelled) setTraits(data); })
      .catch(() => { if (!cancelled) setTraits([]); });
    return () => { cancelled = true; };
  }, []);

  const isPersonality = quizType === "PERSONALITY";
  const isScale = answerType === "SCALE";
  const isYesNo = answerType === "YES_NO";
  const authored = answerType === "SINGLE_CHOICE" || answerType === "MULTI_CHOICE";

  const setOption = (id, patch) =>
    setOptions((o) => o.map((x) => (x.id === id ? { ...x, ...patch } : x)));

  // Changing the answer type reshapes the option list. Done in the handler, not
  // an effect: an effect would run on the first render too and wipe the rows an
  // existing question was opened with.
  function changeAnswerType(next) {
    setAnswerType(next);
    setErrors({});
    if (next === "YES_NO") setOptions((o) => yesNoRows(o));
    else if (next !== "SCALE") {
      setOptions((o) => (o.length >= 2 ? o : [...o, newOption()]));
    }
  }

  function validate() {
    const next = {};
    if (!text.trim()) next.text = "Write the question.";

    if (isScale) {
      // Every existing option has to go, and an answered one cannot.
      if (options.some((o) => o.inUse)) {
        next.options = "This question has already been answered, so it cannot "
          + "become a scale question. Archive it and write a new one instead.";
      }
    } else {
      const filled = options.filter((o) => o.label.trim());
      if (authored && filled.length < 2) {
        // One option is not a choice, it is a statement.
        next.options = "A question needs at least two answer options.";
      }
      const labels = filled.map((o) => o.label.trim().toLowerCase());
      if (new Set(labels).size !== labels.length) {
        next.options = "Two options have the same label — scoring could not tell them apart.";
      }
      if (isPersonality && filled.some((o) => o.traitIds.length === 0)) {
        next.traits = "Every personality option needs at least one trait — an option "
          + "with none can never affect a match score.";
      }
    }

    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function submit(e) {
    e.preventDefault();
    if (busy || !validate()) return;
    onSave?.({
      quizType,
      questionText: text.trim(),
      answerType,
      category,
      required,
      status,
      // Server-side ids only. A negative one is this component's bookkeeping
      // and would be read as "update the row with id -3".
      options: isScale ? [] : options
        .filter((o) => o.label.trim())
        .map((o) => ({
          id: o.id > 0 ? o.id : null,
          label: o.label.trim(),
          weight: isPersonality ? 0 : Number(o.weight) || 0,
          requiresText: false,
          traitIds: isPersonality ? o.traitIds : [],
        })),
    });
  }

  return (
    <Modal
      open={open}
      onClose={busy ? () => {} : onClose}
      title={editing ? "Edit Question" : "Add Question"}
      subtitle={editing ? question.question.questionText : undefined}
      size="lg"
    >
      <form onSubmit={submit} noValidate>
        {error && (
          <p role="alert" className="mb-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}

        {editing ? (
          // Not editable. Moving a question between banks would carry its
          // answers into a quiz that never asked it, and V6 pins an
          // application's attempt to a quiz type so that cannot drift.
          <div className="flex items-center gap-2 rounded-card bg-pw-input px-4 py-3 text-sm text-pw-body">
            <FiLock className="h-4 w-4 shrink-0 text-pw-muted" />
            In the <strong className="font-semibold text-pw-ink">{quizType.toLowerCase()}</strong> quiz.
            A question cannot be moved to another quiz — archive it and write a new one.
          </div>
        ) : (
          <Segmented
            label="Quiz"
            name="quiz-type"
            value={quizType}
            onChange={setQuizType}
            options={[
              { value: "ELIGIBILITY", label: "Eligibility" },
              { value: "PERSONALITY", label: "Personality" },
              { value: "BOARDING", label: "Boarding" },
            ]}
          />
        )}

        <Textarea
          label="Question"
          className="mt-5"
          required
          rows={3}
          placeholder="e.g. Do you have other pets?"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        {errors.text && <p className="mt-1.5 text-sm text-pw-danger">{errors.text}</p>}

        <div className="mt-5 grid gap-5 sm:grid-cols-2">
          <Select label="What it asks about" required
            value={category} onChange={(e) => setCategory(e.target.value)}>
            {CATEGORIES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>

          <Select label="Answer type" required
            value={answerType} onChange={(e) => changeAnswerType(e.target.value)}>
            {ANSWER_TYPES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>
        </div>

        {isScale ? (
          <div className="mt-6 flex gap-3 rounded-card bg-pw-input p-4">
            <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
            <p className="text-sm leading-relaxed text-pw-body">
              A scale question has no answer options. It records a number from
              1 to 5 — the range is fixed by the database, so there is nothing
              here to write.
            </p>
          </div>
        ) : (
          <div className="mt-6">
            <p className="mb-1.5 text-sm font-medium text-pw-ink">Answer options</p>
            <p className="mb-3 text-sm text-pw-muted">
              {isPersonality
                ? "Choosing an option gives the adopter the traits you map to it. Those traits are what the match score compares against each pet."
                : "The weight is added to the applicant's score when they pick that option."}
              {isYesNo && " The two labels are fixed; set what each branch is worth."}
            </p>

            <ul className="space-y-3">
              {options.map((o, i) => (
                <li key={o.id}
                  className={isPersonality ? "rounded-card border border-pw-stroke/50 bg-pw-input/40 p-3" : ""}>
                  <div className="flex items-center gap-3">
                    <input
                      value={o.label}
                      onChange={(e) => setOption(o.id, { label: e.target.value })}
                      readOnly={isYesNo}
                      placeholder={`Option ${i + 1}`}
                      aria-label={`Option ${i + 1} label`}
                      className={`h-12 flex-1 rounded-card border border-pw-stroke/60 px-5 text-[15px] text-pw-ink placeholder:text-pw-muted focus:border-pw-button focus:outline-none focus:ring-2 focus:ring-pw-button/25 ${
                        isYesNo ? "bg-pw-input text-pw-body" : "bg-white"}`}
                    />

                    {/* Weight is meaningless on a personality question --
                        nothing sums it -- so it is not rendered rather than
                        shown and ignored. */}
                    {!isPersonality && (
                      <label className="flex h-12 shrink-0 items-center gap-2 rounded-card border border-pw-stroke/60 bg-pw-input px-3">
                        <span className="text-sm text-pw-muted" aria-hidden="true">W:</span>
                        <input
                          type="number"
                          value={o.weight}
                          onChange={(e) => setOption(o.id, { weight: e.target.value })}
                          aria-label={`Weight for option ${i + 1}`}
                          className="w-14 bg-transparent text-[15px] text-pw-ink focus:outline-none"
                        />
                      </label>
                    )}

                    {/* Yes/No is always exactly two rows, and an option
                        somebody has already chosen can never be removed --
                        fk_answers_option is ON DELETE RESTRICT, so the server
                        would refuse at the end of a long edit. */}
                    <button
                      type="button"
                      onClick={() => setOptions((os) => os.filter((x) => x.id !== o.id))}
                      disabled={isYesNo || o.inUse || options.length <= 2}
                      title={o.inUse ? "Somebody has already chosen this option" : "Remove option"}
                      aria-label={`Remove option ${i + 1}`}
                      className="shrink-0 p-2 text-pw-muted hover:text-pw-danger disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <FiTrash2 className="h-[18px] w-[18px]" />
                    </button>
                  </div>

                  {o.inUse && (
                    <p className="mt-1.5 pl-1 text-xs text-pw-muted">
                      Already chosen by someone taking this quiz — it can be reworded, not removed.
                    </p>
                  )}

                  {isPersonality && (
                    <fieldset className="mt-3">
                      <legend className="mb-2 text-xs font-semibold uppercase tracking-wider text-pw-muted">
                        Traits this option gives
                      </legend>
                      <div className="flex flex-wrap gap-2">
                        {traits.length === 0 && (
                          <p className="text-sm text-pw-muted">Loading traits…</p>
                        )}
                        {traits.map((t) => {
                          const on = o.traitIds.includes(t.id);
                          return (
                            <button
                              key={t.id}
                              type="button"
                              role="checkbox"
                              aria-checked={on}
                              aria-label={`${t.label} for option ${i + 1}`}
                              onClick={() => setOption(o.id, {
                                traitIds: on
                                  ? o.traitIds.filter((x) => x !== t.id)
                                  : [...o.traitIds, t.id],
                              })}
                              className={[
                                "rounded-pill border px-3 py-1.5 text-sm transition-colors",
                                on
                                  ? "border-pw-button bg-pw-sidebar/35 font-medium text-pw-ink"
                                  : "border-pw-stroke/60 bg-white text-pw-body hover:border-pw-stroke",
                              ].join(" ")}
                            >
                              {t.label}
                            </button>
                          );
                        })}
                      </div>
                    </fieldset>
                  )}
                </li>
              ))}
            </ul>

            {errors.options && <p className="mt-2 text-sm text-pw-danger">{errors.options}</p>}
            {errors.traits && <p className="mt-2 text-sm text-pw-danger">{errors.traits}</p>}

            {authored && (
              <button
                type="button"
                onClick={() => setOptions((o) => [...o, newOption()])}
                className="mt-3 flex w-full items-center justify-center gap-2 rounded-card border-2 border-dashed border-pw-stroke px-5 py-3.5 text-sm font-medium text-pw-button hover:border-pw-button hover:bg-pw-input"
              >
                <FiPlus className="h-4 w-4" /> Add option
              </button>
            )}
          </div>
        )}

        <div className="mt-6 grid gap-5 sm:grid-cols-2">
          <Segmented
            label="Save as"
            name="question-status"
            value={status}
            onChange={setStatus}
            options={[
              { value: "ACTIVE", label: "Published" },
              { value: "DRAFT", label: "Draft" },
            ]}
          />

          <label className="flex items-center justify-between self-end rounded-card bg-pw-input px-5 py-4">
            <span className="text-[15px] text-pw-ink">Required question</span>
            <span className="relative inline-flex">
              <input type="checkbox" checked={required}
                onChange={(e) => setRequired(e.target.checked)} className="peer sr-only" />
              <span className="h-7 w-12 rounded-pill bg-pw-stroke transition-colors peer-checked:bg-pw-button peer-focus-visible:ring-2 peer-focus-visible:ring-pw-button/40 peer-focus-visible:ring-offset-2" />
              <span className="pointer-events-none absolute left-1 top-1 h-5 w-5 rounded-full bg-white transition-transform peer-checked:translate-x-5" />
            </span>
          </label>
        </div>

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={busy}>
            {busy ? "Saving…" : editing ? "Save changes" : "Add question"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
