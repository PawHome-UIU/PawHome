import { useCallback, useEffect, useMemo, useState } from "react";
import {
  FiPlus, FiSearch, FiHelpCircle, FiCheckCircle, FiTarget, FiEye,
  FiRefreshCw, FiEdit2, FiEyeOff, FiArchive, FiRotateCcw,
} from "react-icons/fi";
import AdminLayout from "../../components/admin/AdminLayout";
import StatTile from "../../components/admin/StatTile";
import DataTable from "../../components/admin/DataTable";
import ActionMenu from "../../components/ui/ActionMenu";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Tabs from "../../components/ui/Tabs";
import AddQuestionModal from "../../components/admin/AddQuestionModal";
import SuccessModal from "../../components/ui/SuccessModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The three question banks.
 *
 * READS THE REAL DATABASE. It used to render a hard-coded array from
 * data/admin.js and the Add Question modal threw its result away, so the
 * fourteen questions V2 seeds -- the ones an adopter would actually be asked --
 * were invisible here and uneditable.
 *
 * ONE BANK AT A TIME. The tabs are not a filter over a combined list: a
 * question belongs to exactly one quiz and cannot move, so each tab is its own
 * fetch. That also keeps display_order meaningful, since order is per quiz.
 *
 * NOTHING IS EVER DELETED. quiz_answers reference questions and options
 * forever, so retiring is ARCHIVED. The menu offers no delete because the
 * server has no route for one.
 */
const QUIZZES = [
  { key: "ELIGIBILITY", label: "Eligibility Quiz" },
  { key: "PERSONALITY", label: "Personality Matching" },
  { key: "BOARDING", label: "Boarding Quiz" },
];

// whitespace-nowrap matters with the fixed height: a two-word label like
// "Multiple choice" wraps inside a h-6 pill and spills out of it.
const BADGE = "inline-flex h-6 items-center justify-center whitespace-nowrap rounded-pill px-2.5 "
            + "text-[11px] font-semibold leading-none";

const STATUS_TONE = {
  ACTIVE: "bg-green-50 text-green-700",
  DRAFT: "bg-amber-50 text-amber-700",
  ARCHIVED: "bg-pw-input text-pw-muted",
};

const TYPE_LABEL = {
  SINGLE_CHOICE: "Single choice",
  MULTI_CHOICE: "Multiple choice",
  YES_NO: "Yes / No",
  SCALE: "Scale 1–5",
};

export default function QuizQuestionsPage() {
  const [quiz, setQuiz] = useState("ELIGIBILITY");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("All");

  const [rows, setRows] = useState([]);
  const [counts, setCounts] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [busyId, setBusyId] = useState(null);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState(null);   // the QuestionDetail, or null to create
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [notice, setNotice] = useState(null);

  const load = useCallback(async (quizType) => {
    setLoading(true);
    setLoadError("");
    try {
      const [list, tiles] = await Promise.all([
        api.get("/admin/quiz/questions", { params: { quizType } }),
        api.get("/admin/quiz/counts"),
      ]);
      setRows(list.data);
      setCounts(tiles.data);
    } catch (err) {
      // Saying so beats an empty table that reads as "no questions yet".
      setLoadError(apiError(err, "Could not load the question bank."));
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * Switch bank.
   *
   * Clearing the rows and raising the spinner happens HERE, in the handler,
   * not in the effect below. Two reasons, and they point the same way: setState
   * in an effect body cascades an extra render, and without the clear the
   * previous quiz's questions sit under the new tab until the fetch lands --
   * which reads as the wrong bank rather than as loading.
   */
  function switchQuiz(next) {
    setQuiz(next);
    setRows([]);
    setLoading(true);
  }

  // Each bank is its own fetch. Inline rather than a call to load(), so that
  // no state is set synchronously while the effect body is running.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [list, tiles] = await Promise.all([
          api.get("/admin/quiz/questions", { params: { quizType: quiz } }),
          api.get("/admin/quiz/counts"),
        ]);
        if (cancelled) return;
        setRows(list.data);
        setCounts(tiles.data);
        setLoadError("");
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the question bank."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [quiz]);

  async function act(id, path, body, label) {
    setBusyId(id);
    try {
      await api.post(`/admin/quiz/questions/${id}/${path}`, body);
      await load(quiz);
      setNotice({ title: label });
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  /** Open the editor on a question, fetching its options first. */
  async function openEditor(row) {
    setSaveError("");
    if (!row) { setEditing(null); setEditorOpen(true); return; }
    setBusyId(row.id);
    try {
      const { data } = await api.get(`/admin/quiz/questions/${row.id}`);
      setEditing(data);
      setEditorOpen(true);
    } catch (err) {
      setNotice({ title: "Could not open that question", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  /**
   * Save.
   *
   * The modal stays open on failure holding everything typed. The refusals
   * here are ones the author fixes in the form -- an option somebody has
   * already chosen cannot be removed, a personality option needs a trait --
   * and retyping a five-option question to fix one of them would be cruel.
   */
  async function save(payload) {
    setSaving(true);
    setSaveError("");
    try {
      if (editing) await api.put(`/admin/quiz/questions/${editing.question.id}`, payload);
      else await api.post("/admin/quiz/questions", payload);
      setEditorOpen(false);
      setEditing(null);
      await load(quiz);
      setNotice({
        title: editing ? "Question saved" : "Question added",
        message: payload.status === "ACTIVE"
          ? "It will be asked the next time somebody takes this quiz."
          : "Saved as a draft, so it will not be asked yet.",
      });
    } catch (err) {
      setSaveError(apiError(err, "That question could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((r) => {
      if (status !== "All" && r.status !== status.toUpperCase()) return false;
      if (q && !r.questionText.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [rows, query, status]);

  const tabCount = (key) => {
    if (!counts) return undefined;
    return { ELIGIBILITY: counts.eligibility, PERSONALITY: counts.personality,
             BOARDING: counts.boarding }[key];
  };

  return (
    <AdminLayout
      title="Quiz Questions"
      subtitle="Manage the eligibility, personality-matching and boarding quizzes."
      search="Search questions…"
      searchValue={query}
      onSearchChange={setQuery}
      actions={
        <>
          <Button variant="outline" size="sm" onClick={() => load(quiz)} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          <Button size="sm" onClick={() => openEditor(null)}>
            <FiPlus className="h-4 w-4" /> Add Question
          </Button>
        </>
      }
    >
      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile icon={FiHelpCircle} value={String(counts?.total ?? "—")} label="Total Questions" />
        <StatTile icon={FiCheckCircle} value={String(counts?.eligibility ?? "—")} label="Eligibility" />
        <StatTile icon={FiTarget} value={String(counts?.personality ?? "—")} label="Personality" />
        <StatTile icon={FiEye} value={String(counts?.active ?? "—")} label="Published" tone="good" />
      </div>

      <Card className="mt-6 overflow-hidden">
        <div className="px-6 pt-5">
          <Tabs
            value={quiz} onChange={switchQuiz}
            tabs={QUIZZES.map((q) => ({ ...q, count: tabCount(q.key) }))}
          />
        </div>

        <div className="flex flex-wrap items-center gap-3 px-6 py-5">
          <div className="relative min-w-[220px] flex-1">
            <FiSearch className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-pw-muted" />
            <input
              value={query} onChange={(e) => setQuery(e.target.value)}
              placeholder="Search questions…" aria-label="Search questions"
              className="h-11 w-full rounded-pill border border-pw-stroke/60 bg-pw-input pl-11 pr-4 text-sm text-pw-ink placeholder:text-pw-muted focus:border-pw-button focus:outline-none" />
          </div>
          <div className="flex gap-1 rounded-pill bg-pw-input p-1">
            {["All", "Active", "Draft", "Archived"].map((s) => (
              <button key={s} type="button" onClick={() => setStatus(s)} aria-pressed={status === s}
                className={[
                  "rounded-pill px-4 py-2 text-sm transition-colors",
                  status === s ? "bg-white font-medium text-pw-button shadow-card" : "text-pw-body hover:text-pw-button",
                ].join(" ")}>
                {s}
              </button>
            ))}
          </div>
        </div>

        {loadError ? (
          <div className="px-6 py-14 text-center">
            <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
            <Button size="sm" className="mt-5" onClick={() => load(quiz)}>Try again</Button>
          </div>
        ) : loading ? (
          <div className="px-6 py-16 text-center">
            <p className="text-[15px] text-pw-muted">Loading questions…</p>
          </div>
        ) : (
          <DataTable
            title={QUIZZES.find((q) => q.key === quiz).label}
            pageSize={6}
            columns={[
              { key: "questionText", label: "Question", className: "max-w-[420px]" },
              { key: "answerType", label: "Type" },
              { key: "category", label: "Category" },
              { key: "optionCount", label: "Options", align: "right" },
              { key: "status", label: "Status" },
              { key: "actions", label: "", align: "right", className: "w-px whitespace-nowrap" },
            ]}
            rows={visible}
            empty="No questions match those filters."
            render={(row, key) => {
              if (key === "questionText") {
                return (
                  <span className="block max-w-[420px] text-pw-ink">
                    {row.questionText}
                    {!row.required && (
                      <span className="ml-2 text-xs text-pw-muted">(optional)</span>
                    )}
                  </span>
                );
              }

              if (key === "answerType") {
                return (
                  <span className={`${BADGE} bg-pw-input text-pw-button`}>
                    {TYPE_LABEL[row.answerType] ?? row.answerType}
                  </span>
                );
              }

              if (key === "category") {
                return (
                  <span className={`${BADGE} bg-pw-box text-pw-body`}>
                    {row.category.charAt(0) + row.category.slice(1).toLowerCase()}
                  </span>
                );
              }

              // A scale question has no options by design, so a bare 0 would
              // read as "somebody forgot to add them".
              if (key === "optionCount") {
                return row.answerType === "SCALE"
                  ? <span className="text-pw-muted">1–5</span>
                  : <span className="tabular-nums">{row.optionCount}</span>;
              }

              if (key === "status") {
                return (
                  <span className="flex flex-wrap items-center gap-1.5">
                    <span className={`${BADGE} ${STATUS_TONE[row.status]}`}>{row.status}</span>
                    {row.answeredCount > 0 && (
                      // Why some edits will be refused. Better here than as a
                      // surprise at the end of a long edit.
                      <span className={`${BADGE} bg-sky-50 text-sky-700`}>
                        {row.answeredCount} ANSWERED
                      </span>
                    )}
                  </span>
                );
              }

              if (key === "actions") {
                const busy = busyId === row.id;
                return (
                  <span className="flex flex-nowrap items-center justify-end gap-2">
                    <Button size="sm" variant="outline" disabled={busy}
                      onClick={() => openEditor(row)} className="!h-8 !w-[86px] !px-0">
                      <FiEdit2 className="h-3.5 w-3.5" /> Edit
                    </Button>

                    <ActionMenu
                      disabled={busy}
                      label={`Actions for this question`}
                      items={[
                        row.status !== "ACTIVE" && {
                          label: row.status === "ARCHIVED" ? "Bring back and publish" : "Publish",
                          icon: row.status === "ARCHIVED" ? FiRotateCcw : FiEye,
                          onClick: () => act(row.id, "status", { status: "ACTIVE" }, "Question published"),
                        },
                        row.status === "ACTIVE" && {
                          label: "Unpublish to draft", icon: FiEyeOff,
                          onClick: () => act(row.id, "status", { status: "DRAFT" }, "Moved back to draft"),
                        },
                        row.status !== "ARCHIVED" && {
                          // Not "delete". quiz_answers reference this question
                          // forever, so archiving is the only way to retire it
                          // -- and past attempts stay readable.
                          label: "Archive", icon: FiArchive, tone: "danger",
                          onClick: () => act(row.id, "status", { status: "ARCHIVED" }, "Question archived"),
                        },
                      ]}
                    />
                  </span>
                );
              }

              return row[key];
            }}
          />
        )}
      </Card>

      {/* Mounted only while open, so it starts from the question it was given
          without a props-to-state effect. */}
      {editorOpen && (
        <AddQuestionModal
          open
          question={editing}
          busy={saving}
          error={saveError}
          onClose={() => { setEditorOpen(false); setEditing(null); }}
          onSave={save}
        />
      )}

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? "The change has been saved."}
      />
    </AdminLayout>
  );
}
