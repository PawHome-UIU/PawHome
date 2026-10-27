import { useCallback, useEffect, useState } from "react";
import {
  FiPlus, FiClipboard, FiAlertTriangle, FiCheck, FiSkipForward, FiEdit2, FiTrash2,
  FiPaperclip,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import PanelCard from "../../components/portal/PanelCard";
import DataTable from "../../components/admin/DataTable";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import Modal from "../../components/ui/Modal";
import Tabs from "../../components/ui/Tabs";
import { TaskTypePill } from "./StaffDashboard";
import AddVaccinationModal from "../../components/staff/AddVaccinationModal";
import SuccessModal from "../../components/ui/SuccessModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";


const TASK_TYPES = [
  { value: "FEEDING", label: "Feeding" },
  { value: "MEDICATION", label: "Medication" },
  { value: "EXERCISE", label: "Exercise" },
  { value: "GROOMING", label: "Grooming" },
  { value: "WOUND_CARE", label: "Wound care" },
];

const BOX = "h-12 w-full rounded-pill border border-pw-stroke/60 bg-pw-input px-5 "
          + "text-[15px] text-pw-ink focus:border-pw-button focus:outline-none";

/**
 * Record what was actually done.
 *
 * A LOG MAY ANSWER A TASK, OR NOTHING AT ALL. fk_logs_task is nullable on
 * purpose -- "found him limping, called the vet" was never on the rota -- and
 * when a task IS named the server marks it done in the same transaction, so
 * the rota and the record of what happened cannot disagree.
 */
function LogCareTaskModal({ open, onClose, pets, tasks, onSaved }) {
  const [petId, setPetId] = useState("");
  const [taskId, setTaskId] = useState("");
  const [type, setType] = useState("FEEDING");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const open_ = tasks.filter(
    (t) => t.status === "PENDING" && String(t.petId) === String(petId));

  async function save() {
    if (!petId || notes.trim().length === 0) {
      setError("Choose a pet and write what you did.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const { data } = await api.post("/staff/care/logs", {
        petId: Number(petId),
        taskId: taskId ? Number(taskId) : null,
        taskType: type,
        notes: notes.trim(),
      });
      onSaved(data);
      setPetId(""); setTaskId(""); setType("FEEDING"); setNotes("");
    } catch (err) {
      setError(apiError(err, "Could not save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose}
      title="Log Care Task"
      subtitle="Records what actually happened. Logs are append-only."
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button size="sm" onClick={save} disabled={busy}>
            {busy ? "Saving…" : "Save Log"}
          </Button>
        </>
      }>
      <div className="space-y-4">
        {error && (
          <p role="alert" className="rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <span className="mb-1.5 block text-sm font-medium text-pw-ink">Pet</span>
            <select className={BOX} value={petId}
              onChange={(e) => { setPetId(e.target.value); setTaskId(""); }}>
              <option value="">Choose a pet…</option>
              {pets.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div>
            <span className="mb-1.5 block text-sm font-medium text-pw-ink">Task type</span>
            <select className={BOX} value={type} onChange={(e) => setType(e.target.value)}>
              {TASK_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </div>
        </div>

        {/* Only offered once a pet is chosen, and only that pet's open tasks:
            the server refuses a task belonging to a different animal. */}
        {open_.length > 0 && (
          <div>
            <span className="mb-1.5 block text-sm font-medium text-pw-ink">
              Does this complete a scheduled task?
            </span>
            <select className={BOX} value={taskId} onChange={(e) => setTaskId(e.target.value)}>
              <option value="">No — this was ad-hoc</option>
              {open_.map((t) => (
                <option key={t.id} value={t.id}>{t.description}</option>
              ))}
            </select>
          </div>
        )}

        <div>
          <span className="mb-1.5 block text-sm font-medium text-pw-ink">Notes</span>
          <textarea rows={4} value={notes} onChange={(e) => setNotes(e.target.value)}
            placeholder="e.g. Insulin administered. Pet was calm."
            className="w-full rounded-2xl border border-pw-stroke/60 bg-pw-input px-5 py-3 text-[15px] text-pw-ink placeholder:text-pw-muted focus:border-pw-button focus:outline-none" />
        </div>
      </div>
    </Modal>
  );
}

/**
 * Schedule something, or change it.
 *
 * `task` switches this to editing. A task could only be completed or skipped
 * before -- never rescheduled or reassigned -- so a shift change or a vet
 * appointment moving meant deleting the row, which was also impossible.
 *
 * MOVING THE DUE DATE RE-ARMS THE REMINDER. The server clears
 * due_notified_at when dueAt changes (V12), because a new deadline earns a new
 * reminder -- otherwise a task pushed to next week goes quietly past.
 */
function AddCareTaskModal({ open, onClose, pets, staff, onSaved, task = null }) {
  const editing = task != null;
  const [form, setForm] = useState(editing ? {
    petId: String(task.petId ?? ""),
    taskType: task.taskType ?? "FEEDING",
    description: task.description ?? "",
    // datetime-local wants "YYYY-MM-DDTHH:mm"; a LocalDateTime arrives with
    // seconds on the end.
    dueAt: (task.dueAt ?? "").slice(0, 16),
    priority: task.priority ?? "NORMAL",
    assignedTo: task.assignedTo == null ? "" : String(task.assignedTo),
  } : {
    petId: "", taskType: "FEEDING", description: "", dueAt: "",
    priority: "NORMAL", assignedTo: "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function save() {
    if (!form.petId || !form.description.trim() || !form.dueAt) {
      setError("A pet, a description and a due time are all needed.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const payload = {
        petId: Number(form.petId),
        taskType: form.taskType,
        description: form.description.trim(),
        // datetime-local has no zone; the column is DATETIME and the whole
        // application agrees to store UTC, so seconds are appended and the
        // server takes it as given.
        dueAt: `${form.dueAt}:00`,
        priority: form.priority,
        assignedTo: form.assignedTo ? Number(form.assignedTo) : null,
      };
      const { data } = editing
        ? await api.put(`/staff/care/tasks/${task.id}`, payload)
        : await api.post("/staff/care/tasks", payload);
      onSaved(data);
      setForm({ petId: "", taskType: "FEEDING", description: "", dueAt: "",
        priority: "NORMAL", assignedTo: "" });
    } catch (err) {
      setError(apiError(err, "Could not schedule that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose}
      title={editing ? "Edit care task" : "Schedule a care task"}
      subtitle={editing
        ? "Moving the due time makes the reminder due again."
        : "Overdue is worked out from the due time — there is no overdue status to set."}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button size="sm" onClick={save} disabled={busy}>
            {busy ? "Saving…" : editing ? "Save changes" : "Schedule"}
          </Button>
        </>
      }>
      <div className="space-y-4">
        {error && (
          <p role="alert" className="rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <span className="mb-1.5 block text-sm font-medium text-pw-ink">Pet</span>
            <select className={BOX} value={form.petId} onChange={set("petId")}
              disabled={editing}>
              <option value="">Choose a pet…</option>
              {pets.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div>
            <span className="mb-1.5 block text-sm font-medium text-pw-ink">Task type</span>
            <select className={BOX} value={form.taskType} onChange={set("taskType")}>
              {TASK_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </div>
        </div>

        <Input label="What needs doing" value={form.description}
          onChange={set("description")} placeholder="Insulin, 2 units with breakfast" />

        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <span className="mb-1.5 block text-sm font-medium text-pw-ink">Due</span>
            <input type="datetime-local" className={BOX} value={form.dueAt} onChange={set("dueAt")} />
          </div>
          <div>
            <span className="mb-1.5 block text-sm font-medium text-pw-ink">Priority</span>
            <select className={BOX} value={form.priority} onChange={set("priority")}>
              <option value="NORMAL">Normal</option>
              <option value="CRITICAL">Critical</option>
            </select>
          </div>
        </div>

        <div>
          <span className="mb-1.5 block text-sm font-medium text-pw-ink">Assign to</span>
          <select className={BOX} value={form.assignedTo} onChange={set("assignedTo")}>
            {/* Unassigned means the whole shelter's, not nobody's -- the
                overdue sweep tells everyone when there is no assignee. */}
            <option value="">Anyone on shift</option>
            {staff.map((u) => <option key={u.id} value={u.id}>{u.fullName}</option>)}
          </select>
        </div>
      </div>
    </Modal>
  );
}

export default function CareHealthPage() {
  const [tab, setTab] = useState("vaccinations");
  const [vaccOpen, setVaccOpen] = useState(false);
  const [editVacc, setEditVacc] = useState(null);
  const [removeVacc, setRemoveVacc] = useState(null);
  const [savedVacc, setSavedVacc] = useState(null);
  const [logOpen, setLogOpen] = useState(false);

  const [vaccinations, setVaccinations] = useState([]);
  const [pets, setPets] = useState([]);
  const [board, setBoard] = useState({ counts: null, tasks: [], logs: [] });
  const [staff, setStaff] = useState([]);
  const [taskOpen, setTaskOpen] = useState(false);
  const [editTask, setEditTask] = useState(null);
  const [removeTask, setRemoveTask] = useState(null);
  const [busyId, setBusyId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const fetchAll = async () => Promise.all([
    api.get("/staff/vaccinations"),
    api.get("/staff/pets"),
    api.get("/staff/care"),
    api.get("/staff/care/colleagues"),
  ]);

  const apply = ([vax, petRes, careRes, staffRes]) => {
    setVaccinations(vax.data);
    setPets(petRes.data);
    setBoard(careRes.data);
    setStaff(staffRes.data);
  };

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      apply(await fetchAll());
    } catch (err) {
      setLoadError(apiError(err, "Could not load care and health."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetchAll();
        if (!cancelled) apply(res);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load care and health."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /** Every task action returns the whole board, so nothing is patched by hand. */
  async function act(id, path) {
    setBusyId(id);
    try {
      const { data } = await api.post(`/staff/care/tasks/${id}/${path}`);
      setBoard(data);
    } catch (err) {
      setLoadError(apiError(err, "That did not work."));
    } finally {
      setBusyId(null);
    }
  }

  const pending = board.tasks.filter((t) => t.status === "PENDING");
  // The server already worked these out -- PENDING past its due time, and
  // SCHEDULED past its due date -- so the page reads the flags rather than
  // recomputing the same comparisons against a different clock.
  const overdueVaccs = vaccinations.filter((v) => v.overdue);

  const when = (iso) => (iso ? new Date(iso).toLocaleString(undefined,
    { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) : "");

  return (
    <StaffLayout
      title="Care &amp; Health"
      subtitle={board.counts
        ? `${board.counts.pending} open · ${board.counts.overdue} overdue · `
          + `${board.counts.doneToday} done today`
        : "Loading…"}
      actions={
        <>
          <Button size="sm" variant="outline" onClick={() => setVaccOpen(true)}>
            <FiPlus className="h-4 w-4" /> Add Vaccination
          </Button>
          <Button size="sm" variant="outline" onClick={() => setTaskOpen(true)}>
            <FiPlus className="h-4 w-4" /> Schedule Task
          </Button>
          <Button size="sm" onClick={() => setLogOpen(true)}>
            <FiClipboard className="h-4 w-4" /> Log Care Task
          </Button>
        </>
      }
    >
      <PanelCard title="Open Care Tasks">
        {pending.length === 0 ? (
          <p className="px-5 py-10 text-center text-sm text-pw-muted">
            {loading ? "Loading…" : "Nothing outstanding."}
          </p>
        ) : (
          <div className="grid gap-4 p-5 sm:grid-cols-2 xl:grid-cols-3">
            {pending.map((t) => {
              const critical = t.priority === "CRITICAL";
              return (
                <div key={t.id}
                  className={`rounded-2xl border p-4 ${
                    t.overdue ? "border-rose-300 bg-rose-50/40"
                    : critical ? "border-amber-300 bg-amber-50/40"
                    : "border-pw-stroke/50"}`}>
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2.5">
                      <span className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full bg-pw-input text-[11px] font-semibold text-pw-button">
                        {t.petCoverPhotoPath
                          ? <img src={`${API_BASE}/files/${t.petCoverPhotoPath}`} alt=""
                              className="h-full w-full object-cover" />
                          : t.petName.slice(0, 2)}
                      </span>
                      <div>
                        <p className="text-sm font-semibold text-pw-ink">{t.petName}</p>
                        <p className="text-xs text-pw-muted">
                          {t.assignedToName ?? "Anyone on shift"}
                        </p>
                      </div>
                    </div>
                    {/* Overdue outranks critical in the badge, because it is
                        the one that is true right now. Neither is stored. */}
                    {(t.overdue || critical) && (
                      <span className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[10px] font-semibold ${
                        t.overdue ? "bg-rose-100 text-rose-700" : "bg-amber-100 text-amber-800"}`}>
                        <FiAlertTriangle className="h-3 w-3" />
                        {t.overdue ? "OVERDUE" : "CRITICAL"}
                      </span>
                    )}
                  </div>

                  <div className="mt-3 flex items-center justify-between gap-2 rounded-xl bg-pw-input/70 px-3 py-2.5">
                    <span className="flex items-center gap-2">
                      <TaskTypePill type={t.taskType} />
                      <span className="text-sm text-pw-body">{t.description}</span>
                    </span>
                    <span className="shrink-0 text-xs tabular-nums text-pw-muted">
                      {when(t.dueAt)}
                    </span>
                  </div>

                  <div className="mt-3 flex gap-2">
                    <Button size="sm" variant="outline" full disabled={busyId === t.id}
                      onClick={() => act(t.id, "complete")}>
                      <FiCheck className="h-4 w-4" /> Done
                    </Button>
                    {/* Skipped is not done -- see CareTaskStatus. A task
                        nobody did is a fact about the day. */}
                    <Button size="sm" variant="outline" disabled={busyId === t.id}
                      aria-label={`Mark ${t.description} skipped`}
                      onClick={() => act(t.id, "skip")}>
                      <FiSkipForward className="h-4 w-4" />
                    </Button>
                    <Button size="sm" variant="outline" disabled={busyId === t.id}
                      aria-label={`Edit ${t.description}`}
                      onClick={() => setEditTask(t)}>
                      <FiEdit2 className="h-4 w-4" />
                    </Button>
                    <Button size="sm" variant="outline" disabled={busyId === t.id}
                      className="!border-pw-danger !text-pw-danger"
                      aria-label={`Delete ${t.description}`}
                      onClick={() => setRemoveTask(t)}>
                      <FiTrash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </PanelCard>

      {loadError && (
        <div className="mt-5 flex items-start gap-3 rounded-card border border-rose-200 bg-rose-50/70 px-5 py-4">
          <FiAlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-pw-danger" />
          <p className="flex-1 text-[15px] text-pw-danger">{loadError}</p>
          <Button size="sm" variant="outline" onClick={load}>Try again</Button>
        </div>
      )}

      {overdueVaccs.length > 0 && (
        <div className="mt-5 flex items-start gap-3 rounded-card border border-amber-200 bg-amber-50/70 px-5 py-4">
          <FiAlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" />
          <p className="text-[15px] text-amber-900">
            <strong className="font-semibold">{overdueVaccs.length} overdue vaccinations.</strong>{" "}
            {[...new Set(overdueVaccs.map((v) => v.petName))].join(", ")} —{" "}
            <button onClick={() => setTab("vaccinations")} className="font-medium underline">
              open the vaccination records
            </button>.
          </p>
        </div>
      )}

      <Card className="mt-6 overflow-hidden">
        <div className="px-6 pt-5">
          <Tabs
            value={tab} onChange={setTab}
            tabs={[
              { key: "logs", label: "Daily Care Logs", count: board.logs.length },
              { key: "vaccinations", label: "Vaccination Records", count: vaccinations.length },
            ]}
          />
        </div>

        {tab === "logs" ? (
          <DataTable
            resetKey={tab}
            // CARE LOGS ARE THE FASTEST-GROWING TABLE IN THE APP -- every
            // feeding, every walk, every pet, every day. Unpaged this is the
            // one screen that gets slower the longer the shelter is open.
            pageSize={8}
            columns={[
              { key: "petName", label: "Pet" },
              { key: "taskType", label: "Task Type" },
              { key: "loggedAt", label: "Time" },
              { key: "loggedByName", label: "Logged By" },
              { key: "notes", label: "Notes" },
            ]}
            rows={loading ? [] : board.logs}
            render={(row, key) => {
              if (key === "taskType") return <TaskTypePill type={row.taskType} />;
              if (key === "petName") return <span className="font-medium">{row.petName}</span>;
              if (key === "notes") return <span className="text-pw-muted">{row.notes}</span>;
              if (key === "loggedAt") {
                return <span className="tabular-nums">{when(row.loggedAt)}</span>;
              }
              return row[key];
            }}
          />
        ) : (
          <DataTable
            resetKey={tab}
            pageSize={8}
            columns={[
              { key: "petName", label: "Pet" },
              { key: "vaccineName", label: "Vaccine" },
              { key: "administeredOn", label: "Administered" },
              { key: "nextDueOn", label: "Next Due" },
              { key: "administeredBy", label: "Vet" },
              { key: "status", label: "Status" },
              { key: "actions", label: "" },
            ]}
            rows={loading ? [] : vaccinations}
            render={(row, key) => {
              if (key === "petName") return <span className="font-medium">{row.petName}</span>;
              if (key === "nextDueOn") return row.nextDueOn ?? <span className="text-pw-muted">—</span>;
              if (key === "vaccineName") {
                return (
                  <span className="inline-flex items-center gap-1.5">
                    {row.vaccineName}
                    {/* A link, never an embed: the file comes back as a
                        download because a PDF can carry script. */}
                    {row.certificatePath && (
                      <a href={`${API_BASE}/files/${row.certificatePath}`}
                        target="_blank" rel="noreferrer"
                        aria-label={`Certificate for ${row.vaccineName}`}
                        className="text-pw-button hover:underline">
                        <FiPaperclip className="h-3.5 w-3.5" />
                      </a>
                    )}
                  </span>
                );
              }
              if (key === "actions") {
                return (
                  <span className="flex justify-end gap-1.5">
                    <Button variant="outline" size="sm" className="!px-2"
                      aria-label={`Edit the ${row.vaccineName} record for ${row.petName}`}
                      onClick={() => setEditVacc(row)}>
                      <FiEdit2 className="h-3.5 w-3.5" />
                    </Button>
                    <Button variant="outline" size="sm"
                      className="!px-2 !border-pw-danger !text-pw-danger"
                      aria-label={`Delete the ${row.vaccineName} record for ${row.petName}`}
                      onClick={() => setRemoveVacc(row)}>
                      <FiTrash2 className="h-3.5 w-3.5" />
                    </Button>
                  </span>
                );
              }
              if (key === "status") {
                // Derived on the server, never stored: V1 is explicit that a
                // stored "overdue" is a copy of today's date frozen into a row.
                const overdue = row.overdue;
                const label = overdue ? "OVERDUE" : row.status;
                const tone = overdue ? "bg-rose-50 text-rose-700"
                  : row.status === "COMPLETED" ? "bg-green-50 text-green-700"
                  : "bg-amber-50 text-amber-700";
                return <span className={`rounded-pill px-2.5 py-1 text-[11px] font-semibold ${tone}`}>{label}</span>;
              }
              return row[key];
            }}
          />
        )}
      </Card>

      <AddVaccinationModal
        open={vaccOpen}
        pets={pets}
        onClose={() => setVaccOpen(false)}
        onSave={() => { setVaccOpen(false); setSavedVacc(true); load(); }}
      />

      <SuccessModal
        open={savedVacc != null}
        onClose={() => setSavedVacc(null)}
        title="Vaccination recorded"
        message="It is on the pet's health record now, and on their profile."
      />
      <LogCareTaskModal
        open={logOpen}
        pets={pets}
        tasks={board.tasks}
        onClose={() => setLogOpen(false)}
        onSaved={(data) => { setBoard(data); setLogOpen(false); }}
      />

      {/* Keyed and mounted only while open, so the form seeds itself from the
          record with a plain useState initialiser rather than an effect. */}
      {editVacc && (
        <AddVaccinationModal
          key={editVacc.id}
          open
          record={editVacc}
          onClose={() => setEditVacc(null)}
          onSave={() => { setEditVacc(null); setSavedVacc(true); load(); }}
        />
      )}

      <Modal
        open={removeVacc != null}
        onClose={() => setRemoveVacc(null)}
        title={removeVacc ? `Delete the ${removeVacc.vaccineName} record?` : ""}
        subtitle={removeVacc
          ? `For ${removeVacc.petName}. This cannot be undone.`
          : ""}
        size="sm"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setRemoveVacc(null)}>
              Cancel
            </Button>
            <Button size="sm" className="!bg-pw-danger hover:!bg-rose-700"
              onClick={async () => {
                const row = removeVacc;
                setRemoveVacc(null);
                try {
                  await api.delete(`/staff/pets/${row.petId}/vaccinations/${row.id}`);
                  await load();
                } catch (err) {
                  setLoadError(apiError(err, "Could not delete that record."));
                }
              }}>
              Delete
            </Button>
          </>
        }
      >
        <p className="text-[15px] leading-relaxed text-pw-body">
          A record deleted here is gone, along with its certificate. Whether the
          pet counts as vaccinated is worked out from the records that remain,
          so removing a completed dose can change it.
        </p>
      </Modal>

      {editTask && (
        <AddCareTaskModal
          key={editTask.id}
          open
          task={editTask}
          pets={pets}
          staff={staff}
          onClose={() => setEditTask(null)}
          onSaved={(data) => { setBoard(data); setEditTask(null); }}
        />
      )}

      <Modal
        open={removeTask != null}
        onClose={() => setRemoveTask(null)}
        title={removeTask ? "Delete this task?" : ""}
        subtitle={removeTask ? `${removeTask.description} · ${removeTask.petName}` : ""}
        size="sm"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setRemoveTask(null)}>
              Cancel
            </Button>
            <Button size="sm" className="!bg-pw-danger hover:!bg-rose-700"
              onClick={async () => {
                const row = removeTask;
                setRemoveTask(null);
                try {
                  const { data } = await api.delete(`/staff/care/tasks/${row.id}`);
                  setBoard(data);
                } catch (err) {
                  setLoadError(apiError(err, "Could not delete that task."));
                }
              }}>
              Delete
            </Button>
          </>
        }
      >
        <p className="text-[15px] leading-relaxed text-pw-body">
          Any care log written against this task is kept — it becomes an ad-hoc
          entry rather than disappearing with the schedule. If the work was
          done, mark it Done instead so the record says so.
        </p>
      </Modal>

      <AddCareTaskModal
        open={taskOpen}
        pets={pets}
        staff={staff}
        onClose={() => setTaskOpen(false)}
        onSaved={(data) => { setBoard(data); setTaskOpen(false); }}
      />
    </StaffLayout>
  );
}
