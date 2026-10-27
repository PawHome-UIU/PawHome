import { useEffect, useState } from "react";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select, Textarea, Segmented, FileDrop } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * Add Vaccination.
 *
 * THE VACCINE LIST IS FETCHED, NOT HARD-CODED, and the version that was here
 * proves why. Its own comment argued that free text would make "Rabies",
 * "rabies" and "Rabies booster" three different vaccines -- and then listed
 * eight names typed by hand, two of which ("DHPP", "FeLV") did not match the
 * seeded rows ("DHPP (Distemper/Parvo)", "Feline Leukemia (FeLV)") at all. The
 * server now returns the real rows with their ids, already filtered to what
 * this species is given, so a cat can no longer be offered a distemper shot.
 *
 * THE STATUS SEGMENT USED TO OFFER "OVERDUE". vaccinations.status is an ENUM of
 * COMPLETED and SCHEDULED, and V1 is explicit that overdue is a query on
 * next_due_on rather than a stored value -- so saving that third option would
 * have been rejected by the database every time. Two options now, matching the
 * column.
 *
 * THE CERTIFICATE IS UPLOADED SECOND, not folded into the create. It hangs off
 * a row that does not exist until the record is saved, so there is nothing to
 * attach it to before then -- and one request that can half succeed would
 * leave a vaccination recorded with the document silently dropped, which is
 * what this field used to do when nothing on the server accepted a file.
 */
const EMPTY = {
  vaccineTypeId: "", administered: "", nextDue: "", vet: "", batch: "",
  status: "COMPLETED", notes: "",
};

/**
 * `record` switches this from adding to editing.
 *
 * The pet is FIXED when editing, and not because a picker would be awkward:
 * the server refuses a record that belongs to a different animal, so offering
 * to move one between pets would be a control that always fails. A record
 * entered against the wrong pet is deleted and re-entered.
 */
export default function AddVaccinationModal({
  open, onClose, pet, pets = [], onSave, record = null,
}) {
  const editing = record != null;
  const [form, setForm] = useState(editing ? {
    vaccineTypeId: String(record.vaccineTypeId ?? ""),
    administered: record.administeredOn ?? "",
    nextDue: record.nextDueOn ?? "",
    vet: record.administeredBy ?? "",
    batch: record.batchLot ?? "",
    status: record.status ?? "COMPLETED",
    notes: record.notes ?? "",
  } : EMPTY);
  const [petId, setPetId] = useState("");
  // Tagged with the pet they belong to rather than cleared on change: an
  // effect that resets state on the way out sets state synchronously in an
  // effect body, and comparing the tag also stops the previous pet's list
  // showing for the moment before the new one arrives.
  const [types, setTypes] = useState({ petId: null, list: [] });
  const [cert, setCert] = useState(null);
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState("");

  const chosenPetId = record?.petId ?? pet?.id ?? (petId ? Number(petId) : null);

  // The list depends on the animal, so it cannot load until one is chosen.
  // setState only after the await, which is what keeps this out of the
  // synchronous-setState-in-an-effect rule.
  useEffect(() => {
    if (!open || !chosenPetId) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/staff/pets/${chosenPetId}/vaccinations/types`);
        if (!cancelled) setTypes({ petId: chosenPetId, list: data });
      } catch {
        // The Select simply stays empty and validate() refuses the submit.
      }
    })();
    return () => { cancelled = true; };
  }, [open, chosenPetId]);

  const set = (k) => (e) =>
    setForm((f) => ({ ...f, [k]: e?.target ? e.target.value : e }));

  function validate() {
    const next = {};
    // Opened from the toolbar there is no pet yet, and a vaccination row has a
    // NOT NULL pet_id -- so the picker is required exactly when the caller
    // could not say whose record this is.
    if (!pet && !editing && !petId) next.pet = "Choose which pet this vaccination is for.";
    if (!form.vaccineTypeId) next.vaccine = "Choose which vaccine was given.";
    // administered_on is NOT NULL, for a scheduled row as much as a completed
    // one -- there it is the date of the dose this booster follows.
    if (!form.administered) next.administered = "When was this dose given?";
    if (form.status === "SCHEDULED" && !form.nextDue) {
      next.nextDue = "A scheduled vaccination needs a due date, or nothing will remind anyone.";
    }
    // ck_vacc_next_due says next_due_on > administered_on. Saying it here puts
    // the message on the field rather than arriving as a constraint violation.
    if (form.administered && form.nextDue && form.nextDue <= form.administered) {
      next.nextDue = "The next dose has to be due after this one was given.";
    }
    if (!form.vet.trim()) next.vet = "Who administered it?";
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  async function submit(e) {
    e.preventDefault();
    setSubmitError("");
    if (!validate()) return;

    setBusy(true);
    try {
      const payload = {
        vaccineTypeId: Number(form.vaccineTypeId),
        administeredOn: form.administered,
        nextDueOn: form.nextDue || null,
        administeredBy: form.vet.trim(),
        batchLot: form.batch.trim() || null,
        status: form.status,
        notes: form.notes.trim() || null,
      };

      const { data } = editing
        ? await api.put(
            `/staff/pets/${chosenPetId}/vaccinations/${record.id}`, payload)
        : await api.post(`/staff/pets/${chosenPetId}/vaccinations`, payload);

      // Second call, and allowed to fail on its own: the record is already
      // saved by now, so a rejected file must not read as "nothing was saved".
      if (cert?.[0]) {
        // The HIGHEST id, not the first row. The list comes back ordered by
        // the date the dose was given, so a certificate for a shot recorded
        // late -- a booster given last year, typed in today -- would otherwise
        // be attached to whichever record happens to sort first.
        const created = editing
            ? record
            : data.vaccinations.reduce((a, b) => (b.id > a.id ? b : a));
        const body = new FormData();
        body.append("file", cert[0]);
        try {
          await api.post(
            `/staff/pets/${chosenPetId}/vaccinations/${created.id}/certificate`, body);
        } catch (err) {
          onSave?.(data);
          setSubmitError(apiError(err,
            "The record saved, but the certificate did not upload. Add it from the record."));
          return;
        }
      }

      onSave?.(data);
      reset();
    } catch (err) {
      setSubmitError(apiError(err, "Could not save that record."));
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setForm(EMPTY);
    setPetId("");
    setCert(null);
    setErrors({});
    setSubmitError("");
  }

  function close() {
    reset();
    onClose();
  }

  return (
    <Modal open={open} onClose={close}
      title={editing ? "Edit Vaccination" : "Add Vaccination"} size="md">
      <form onSubmit={submit} noValidate>
        {submitError && (
          <p role="alert" className="mb-5 rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {submitError}
          </p>
        )}

        {!pet && !editing && (
          <div className="mb-6">
            <Select label="Pet" required value={petId} onChange={(e) => setPetId(e.target.value)}>
              <option value="">Select a pet…</option>
              {pets.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name} — {x.breed}
                </option>
              ))}
            </Select>
            {errors.pet && <p className="mt-1.5 text-sm text-pw-danger">{errors.pet}</p>}
          </div>
        )}

        {pet && (
          <div className="mb-6 flex items-center gap-4 rounded-pill bg-pw-input p-3">
            <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-pw-sidebar text-sm font-semibold text-white">
              {pet.name.trim()[0]}
            </span>
            <div>
              <p className="text-[15px] font-semibold text-pw-ink">{pet.name}</p>
              <p className="text-sm text-pw-muted">
                {pet.species ? `${pet.species} • ` : ""}{pet.breed}
              </p>
            </div>
          </div>
        )}

        <Select
          label="Vaccine Name"
          required
          value={form.vaccineTypeId}
          onChange={set("vaccineTypeId")}
        >
          <option value="">
            {chosenPetId ? "Select a vaccine…" : "Choose a pet first…"}
          </option>
          {(types.petId === chosenPetId ? types.list : [])
            .map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
        </Select>
        {errors.vaccine && (
          <p className="mt-1.5 text-sm text-pw-danger">{errors.vaccine}</p>
        )}

        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <Input
            label="Date Administered"
            required
            type="date"
            value={form.administered}
            onChange={set("administered")}
            error={errors.administered}
          />
          <Input
            label="Next Due Date"
            type="date"
            value={form.nextDue}
            onChange={set("nextDue")}
            error={errors.nextDue}
          />
        </div>

        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <Input
            label="Administered by / Vet"
            required
            placeholder="Dr. Smith"
            value={form.vet}
            onChange={set("vet")}
            error={errors.vet}
          />
          <Input
            label="Batch / Lot # (Optional)"
            placeholder="Vax-9002-X"
            value={form.batch}
            onChange={set("batch")}
          />
        </div>

        <Segmented
          label="Status"
          name="vax-status"
          className="mt-5"
          value={form.status}
          onChange={(v) => setForm((f) => ({ ...f, status: v }))}
          options={[
            { value: "COMPLETED", label: "Completed" },
            { value: "SCHEDULED", label: "Scheduled" },
          ]}
        />

        <FileDrop
          label={record?.certificatePath
            ? "Replace the certificate (optional)"
            : "Vaccination Certificate (optional)"}
          className="mt-5"
          accept=".pdf,.jpg,.jpeg,.png,.webp"
          hint="PDF, JPG, PNG or WebP."
          value={cert}
          onChange={setCert}
        />

        <Textarea
          label="Notes"
          className="mt-5"
          rows={3}
          placeholder="Add any special observations…"
          value={form.notes}
          onChange={set("notes")}
        />

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={close} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={busy}>
            {busy ? "Saving…" : editing ? "Save changes" : "Save Vaccination"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
