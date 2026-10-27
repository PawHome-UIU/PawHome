import { useEffect, useState } from "react";
import {
  FiPlus, FiTrash2, FiPaperclip, FiCheckCircle, FiAlertTriangle,
} from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select, Segmented, FileDrop } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * A pet's vaccination record, where the owner's pets actually live.
 *
 * THIS IS THE PAGE THAT MAKES THE FEATURE REACHABLE. The certificate link
 * existed on the catalogue detail page, which an owner has no route to for
 * their own animal -- so in practice nobody could ever see one. Records belong
 * beside the pet they describe.
 *
 * ADDING IS ONLY OFFERED FOR A PET FROM HOME. An adopted pet's history is the
 * shelter's record of its own work: the owner reads it and the shelter writes
 * it, which is the same rule that governs editing the pet's details. The
 * server enforces it either way -- this only decides which buttons to draw.
 *
 * THE STATUS CHOICE IS NOT DECORATION. A COMPLETED dose is what makes a pet
 * count as vaccinated; a SCHEDULED one with a due date is what the reminder
 * job looks for. Recording only completed doses would mean never being
 * reminded about the next one.
 */
const EMPTY = {
  vaccineTypeId: "", administeredOn: "", nextDueOn: "",
  administeredBy: "", status: "COMPLETED",
};

export default function PetHealthModal({ open, onClose, pet, onChanged }) {
  const [health, setHealth] = useState(null);
  const [types, setTypes] = useState([]);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState(EMPTY);
  const [cert, setCert] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // MAY THIS PERSON ADD A RECORD. `editable` is the server's own answer --
  // the pet is theirs and is not away on a boarding stay -- and it is true for
  // an adopted pet, which it did not used to be. A dog rehomed two years ago
  // gets a booster every year, and under the old `shelterId == null` test the
  // only party allowed to record it was a shelter that had not seen the animal
  // since.
  //
  // DELETING IS A DIFFERENT QUESTION AND IT IS PER ROW: v.deletable. The
  // shelter's own history stays.
  const canRecord = pet?.editable ?? (pet?.shelterId == null);
  const fromShelter = pet?.shelterId != null;

  useEffect(() => {
    if (!open || !pet) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const calls = [api.get(`/pets/${pet.id}/health`)];
        if (canRecord) calls.push(api.get(`/my-pets/${pet.id}/vaccinations/types`));
        const [healthRes, typeRes] = await Promise.all(calls);
        if (cancelled) return;
        setHealth(healthRes.data);
        setTypes(typeRes?.data ?? []);
      } catch (err) {
        if (!cancelled) setError(apiError(err, "Could not load the health record."));
      }
    })();
    return () => { cancelled = true; };
  }, [open, pet, canRecord]);

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function save() {
    if (!form.vaccineTypeId || !form.administeredOn || !form.administeredBy.trim()) {
      setError("Choose a vaccine, the date it was given, and who gave it.");
      return;
    }
    // ck_vacc_next_due: strictly after the dose. Said here so the message
    // lands on the form rather than arriving as a constraint violation.
    if (form.nextDueOn && form.nextDueOn <= form.administeredOn) {
      setError("The next dose has to be due after this one was given.");
      return;
    }
    if (form.status === "SCHEDULED" && !form.nextDueOn) {
      setError("A scheduled dose needs a due date, or nothing will remind you.");
      return;
    }

    setBusy(true);
    setError("");
    try {
      const { data } = await api.post(`/my-pets/${pet.id}/vaccinations`, {
        vaccineTypeId: Number(form.vaccineTypeId),
        administeredOn: form.administeredOn,
        nextDueOn: form.nextDueOn || null,
        administeredBy: form.administeredBy.trim(),
        batchLot: null,
        status: form.status,
        notes: null,
      });

      // The certificate is a second call against the row that now exists --
      // it has nothing to hang off until then. Highest id, not the first row:
      // the list is ordered by the date the dose was given.
      let latest = data;
      if (cert?.[0]) {
        const created = data.vaccinations.reduce((a, b) => (b.id > a.id ? b : a));
        const body = new FormData();
        body.append("file", cert[0]);
        latest = (await api.post(
          `/my-pets/${pet.id}/vaccinations/${created.id}/certificate`, body)).data;
      }

      setHealth(latest);
      setForm(EMPTY);
      setCert(null);
      setAdding(false);
      onChanged?.();
    } catch (err) {
      setError(apiError(err, "Could not save that."));
    } finally {
      setBusy(false);
    }
  }

  async function remove(id) {
    setBusy(true);
    setError("");
    try {
      const { data } = await api.delete(`/my-pets/${pet.id}/vaccinations/${id}`);
      setHealth(data);
      onChanged?.();
    } catch (err) {
      setError(apiError(err, "Could not remove that record."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={pet ? `${pet.name}'s health` : "Health"}
      subtitle={!canRecord
        ? "Kept by the shelter that rehomed them."
        : fromShelter
          ? "The shelter's history, and whatever you add since."
          : "Records you keep for a pet from home."}
      footer={
        <>
          {canRecord && !adding && (
            <Button variant="outline" size="sm" onClick={() => setAdding(true)}>
              <FiPlus className="h-4 w-4" /> Add a record
            </Button>
          )}
          <Button size="sm" onClick={onClose}>Done</Button>
        </>
      }
    >
      {error && (
        <p role="alert" className="mb-4 rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
          {error}
        </p>
      )}

      {health && (
        <p className={`mb-5 flex items-center gap-2 rounded-card px-4 py-3 text-[15px] ${
          health.vaccinated ? "bg-green-50 text-green-800" : "bg-pw-input text-pw-body"}`}>
          {health.vaccinated
            ? <><FiCheckCircle className="h-4 w-4" /> Up to date on vaccinations.</>
            : <><FiAlertTriangle className="h-4 w-4 text-amber-600" />
                Not up to date — either nothing recorded, or a dose is overdue.</>}
        </p>
      )}

      {!health ? (
        <p className="py-8 text-center text-sm text-pw-muted">Loading…</p>
      ) : health.vaccinations.length === 0 ? (
        <p className="py-6 text-center text-sm text-pw-muted">
          {canRecord
            ? "Nothing recorded yet. Add what your vet has given them."
            : "The shelter has not recorded any vaccinations."}
        </p>
      ) : (
        <ul className="divide-y divide-pw-stroke/30">
          {health.vaccinations.map((v) => (
            <li key={v.id} className="flex items-start justify-between gap-3 py-3">
              <div className="min-w-0">
                <p className="text-[15px] font-medium text-pw-ink">{v.vaccineName}</p>
                <p className="mt-0.5 text-sm text-pw-muted">
                  {v.status === "SCHEDULED" ? `Due ${v.nextDueOn}` : `Given ${v.administeredOn}`}
                  {v.administeredBy && ` · ${v.administeredBy}`}
                  {v.overdue && (
                    <span className="ml-1.5 rounded-pill bg-rose-50 px-1.5 py-0.5 text-[10px] font-semibold text-pw-danger">
                      overdue
                    </span>
                  )}
                </p>
                {/* A link, never an embed: the file comes back as a download
                    because a PDF can carry script. */}
                {v.certificatePath && (
                  <a href={`${API_BASE}/files/${v.certificatePath}`}
                    target="_blank" rel="noreferrer"
                    className="mt-1 inline-flex items-center gap-1 text-sm font-medium text-pw-button hover:underline">
                    <FiPaperclip className="h-3.5 w-3.5" /> Certificate
                  </a>
                )}
              </div>
              {/* PER ROW. The shelter's entries on an adopted pet are its
                  record of its own work and are not the owner's to erase --
                  the server says which is which in `deletable` and checks
                  again on the request, because a button that is not drawn is
                  not a rule. */}
              {v.deletable ? (
                <Button variant="outline" size="sm" className="!px-2 !border-pw-danger !text-pw-danger"
                  disabled={busy} aria-label={`Remove the ${v.vaccineName} record`}
                  onClick={() => remove(v.id)}>
                  <FiTrash2 className="h-3.5 w-3.5" />
                </Button>
              ) : canRecord && fromShelter ? (
                <span className="shrink-0 rounded-pill bg-pw-box px-2.5 py-1 text-[10px] font-medium text-pw-muted">
                  shelter
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {adding && (
        <div className="mt-6 space-y-4 border-t border-pw-stroke/40 pt-5">
          <Select label="Vaccine" required value={form.vaccineTypeId}
            onChange={set("vaccineTypeId")}>
            <option value="">Choose a vaccine…</option>
            {types.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </Select>

          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Date given" type="date" required
              value={form.administeredOn} onChange={set("administeredOn")} />
            <Input label="Next dose due" type="date" hint="Optional"
              value={form.nextDueOn} onChange={set("nextDueOn")} />
          </div>

          <Input label="Vet or clinic" required placeholder="Dr Smith, Bay Vets"
            value={form.administeredBy} onChange={set("administeredBy")} />

          <Segmented
            label="Status"
            name="owner-vax-status"
            value={form.status}
            onChange={(v) => setForm((f) => ({ ...f, status: v }))}
            options={[
              { value: "COMPLETED", label: "Given" },
              { value: "SCHEDULED", label: "Booked" },
            ]}
          />

          <FileDrop
            label="Certificate (optional)"
            accept=".pdf,.jpg,.jpeg,.png,.webp"
            hint="PDF, JPG, PNG or WebP."
            value={cert}
            onChange={setCert}
          />

          <div className="flex justify-end gap-3">
            <Button variant="outline" size="sm" disabled={busy}
              onClick={() => { setAdding(false); setForm(EMPTY); setCert(null); setError(""); }}>
              Cancel
            </Button>
            <Button size="sm" disabled={busy} onClick={save}>
              {busy ? "Saving…" : "Save record"}
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
}
