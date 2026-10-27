import { useEffect, useMemo, useState } from "react";
import { FiShield } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import Checkbox from "../ui/Checkbox";
import { Select, Textarea, FileDrop } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The booking itself -- everything the quiz is not.
 *
 * THE QUIZ AND THIS FORM COLLECT DIFFERENT KINDS OF THING, which is why they
 * are two steps and not one. The quiz is a bank of questions an admin can edit
 * and whose answers are scored; these are COLUMNS -- shelter_id, start_date,
 * end_date, emergency_contact, vet_care_authorised -- plus rows of
 * boarding_instructions. No amount of editing the question bank can produce a
 * start date, and no schema change should be needed to add a question.
 *
 * THE ESTIMATE IS A QUOTE, NOT A PRICE. It is computed here from the chosen
 * shelter's nightly rate purely so the customer can see it before committing;
 * the number that ends up on the request is calculated again on the server and
 * frozen into estimated_cost, because a total the browser sends is a customer
 * setting their own bill.
 */
const CARE = [
  { key: "DIETARY", label: "Dietary needs",
    placeholder: "Feeding times, brand, anything they will not eat…" },
  { key: "MEDICATION", label: "Medication",
    placeholder: "Dosages and timings, e.g. 1 tablet with breakfast…" },
  { key: "COMFORT_PLAY", label: "Comfort & play",
    placeholder: "Favourite toy, bedtime routine, what settles them…" },
  { key: "EXERCISE", label: "Exercise",
    placeholder: "How much walking, and anything to avoid…" },
];

/**
 * Today, in the browser's own timezone.
 *
 * NOT toISOString().slice(0,10), which is UTC: at 03:00 in Dhaka that returns
 * yesterday, and the date input would then happily offer a day the server
 * rejects as being in the past.
 */
function today() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** Nights between two yyyy-mm-dd strings, parsed locally for the same reason. */
function nightsBetween(a, b) {
  if (!a || !b) return 0;
  const [ay, am, ad] = a.split("-").map(Number);
  const [by, bm, bd] = b.split("-").map(Number);
  const ms = new Date(by, bm - 1, bd) - new Date(ay, am - 1, ad);
  return Math.round(ms / 86400000);
}

export default function BoardingRequestModal({ open, onClose, pet, attemptId, onDone }) {
  const [shelters, setShelters] = useState([]);
  const [form, setForm] = useState({
    shelterId: "", startDate: "", endDate: "", emergencyContact: "",
    vetCareAuthorised: false,
  });
  const [care, setCare] = useState({});
  const [errors, setErrors] = useState({});

  // The optional vaccination proof. A shelter taking an animal for a week
  // wants to know it is vaccinated, and for a pet from home there is nobody
  // but the owner who can say so -- the shelter keeps its own records for the
  // animals it rehomed, which is why this block is only offered for a
  // household pet.
  const [vax, setVax] = useState({ vaccineTypeId: "", administeredOn: "", administeredBy: "" });
  const [vaxTypes, setVaxTypes] = useState([]);
  const [cert, setCert] = useState(null);
  const canRecordVax = pet?.shelterId == null;
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState("");

  useEffect(() => {
    if (!canRecordVax || !pet) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/my-pets/${pet.id}/vaccinations/types`);
        if (!cancelled) setVaxTypes(data);
      } catch {
        // The block simply offers nothing; the request itself is unaffected.
      }
    })();
    return () => { cancelled = true; };
  }, [canRecordVax, pet]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/shelters");
        if (!cancelled) setShelters(data);
      } catch {
        // Not fatal to the modal: the Select simply has nothing in it, and the
        // submit below still refuses without a shelter.
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const set = (k) => (e) =>
    setForm((f) => ({ ...f, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value }));

  const shelter = useMemo(
    () => shelters.find((s) => String(s.id) === String(form.shelterId)) ?? null,
    [shelters, form.shelterId]);

  const nights = nightsBetween(form.startDate, form.endDate);
  const estimate = shelter && nights > 0
    ? Number(shelter.boardingRateDaily ?? 0) * nights : null;

  function validate() {
    const next = {};
    if (!form.shelterId) next.shelterId = "Choose a shelter.";
    if (!form.startDate) next.startDate = "Choose a drop-off date.";
    if (!form.endDate) next.endDate = "Choose a collection date.";
    // ck_boarding_dates says end > start. Saying it here too turns a constraint
    // violation into a message on the field that is wrong.
    if (form.startDate && form.endDate && nights < 1) {
      next.endDate = "Collection has to be at least one night after drop-off.";
    }
    if (!form.emergencyContact.trim()) {
      next.emergencyContact = "Give us someone to call in an emergency.";
    }
    // The whole block is optional, but half of it is not a record: a
    // vaccination row needs a vaccine, a date and who gave it, all NOT NULL.
    const startedVax = cert?.length || vax.vaccineTypeId || vax.administeredOn
      || vax.administeredBy.trim();
    if (startedVax) {
      if (!vax.vaccineTypeId) next.vaccineTypeId = "Which vaccine is this?";
      if (!vax.administeredOn) next.administeredOn = "When was it given?";
      if (!vax.administeredBy.trim()) next.administeredBy = "Who gave it?";
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  async function submit(e) {
    e.preventDefault();
    setSubmitError("");
    if (!validate()) return;

    setBusy(true);
    try {
      const { data } = await api.post("/boarding", {
        petId: pet.id,
        shelterId: Number(form.shelterId),
        startDate: form.startDate,
        endDate: form.endDate,
        quizAttemptId: attemptId ?? null,
        emergencyContact: form.emergencyContact.trim(),
        vetCareAuthorised: form.vetCareAuthorised,
        // Blank boxes are not empty instructions -- they are boxes nobody
        // filled in, and a row of empty TEXT helps no one read the card.
        instructions: CARE
          .filter((c) => (care[c.key] ?? "").trim().length > 0)
          .map((c) => ({ category: c.key, body: care[c.key].trim() })),
      });
      // THE BOARDING REQUEST FIRST, the record second. The request is what
      // the person came here to send; a rejected certificate must not read as
      // "your booking failed". So a failure here is reported on its own and
      // the booking still goes through.
      if (vax.vaccineTypeId) {
        try {
          const { data: health } = await api.post(`/my-pets/${pet.id}/vaccinations`, {
            vaccineTypeId: Number(vax.vaccineTypeId),
            administeredOn: vax.administeredOn,
            nextDueOn: null,
            administeredBy: vax.administeredBy.trim(),
            batchLot: null,
            status: "COMPLETED",
            notes: null,
          });
          if (cert?.[0]) {
            // Highest id, not the first row: the list comes back ordered by
            // the date the dose was given, not by when it was typed in.
            const created = health.vaccinations.reduce((a, b) => (b.id > a.id ? b : a));
            const body = new FormData();
            body.append("file", cert[0]);
            await api.post(
              `/my-pets/${pet.id}/vaccinations/${created.id}/certificate`, body);
          }
        } catch {
          // Deliberately quiet on the record: the booking is sent, and the
          // owner can add the certificate from My Pets → Health.
        }
      }

      onDone?.(data);
    } catch (err) {
      setSubmitError(apiError(err, "Could not send that request."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Request Boarding"
      subtitle={pet ? `For ${pet.name}` : undefined}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button size="sm" type="submit" form="boarding-request" disabled={busy}>
            {busy ? "Sending…" : "Send request"}
          </Button>
        </>
      }
    >
      <form id="boarding-request" onSubmit={submit} className="space-y-5" noValidate>
        {submitError && (
          <p role="alert" className="rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {submitError}
          </p>
        )}

        <Select label="Shelter" required value={form.shelterId} onChange={set("shelterId")}>
          <option value="">Choose a shelter…</option>
          {shelters.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} — {s.city}, {s.state}
              {s.boardingRateDaily != null && ` (৳${Number(s.boardingRateDaily).toFixed(2)}/night)`}
            </option>
          ))}
        </Select>
        {errors.shelterId && <p className="text-sm text-pw-danger">{errors.shelterId}</p>}

        <div className="grid gap-4 sm:grid-cols-2">
          <Input label="Drop-off" type="date" required min={today()}
            value={form.startDate} onChange={set("startDate")} error={errors.startDate} />
          <Input label="Collection" type="date" required min={form.startDate || today()}
            value={form.endDate} onChange={set("endDate")} error={errors.endDate} />
        </div>

        {estimate != null && (
          <p className="rounded-card bg-pw-input px-4 py-3 text-sm text-pw-body">
            {nights} night{nights === 1 ? "" : "s"} at ৳{Number(shelter.boardingRateDaily ?? 0).toFixed(2)}
            {" "}— estimated <span className="font-semibold text-pw-ink">৳{estimate.toFixed(2)}</span>.
            The shelter confirms the final figure when they accept.
          </p>
        )}

        <Input label="Emergency contact" required
          placeholder="Jane Doe, (555) 012-3456"
          value={form.emergencyContact} onChange={set("emergencyContact")}
          error={errors.emergencyContact} />

        <div className="rounded-card bg-pw-input px-4 py-3.5">
          <Checkbox
            label="I authorise emergency veterinary care if it is needed"
            checked={form.vetCareAuthorised}
            onChange={set("vetCareAuthorised")}
          />
          <p className="mt-2 flex gap-2 text-xs text-pw-muted">
            <FiShield className="mt-0.5 h-3.5 w-3.5 shrink-0 text-pw-button" />
            Kept as its own field rather than buried in your answers, so staff
            can see it at a glance in an emergency.
          </p>
        </div>

        {canRecordVax && (
          <div className="rounded-card border border-pw-stroke/50 p-4">
            <p className="text-sm font-medium text-pw-ink">Vaccination proof (optional)</p>
            <p className="mb-4 mt-1 text-sm text-pw-muted">
              Shelters ask for this before a stay. Leave it blank and you can
              add it later from My Pets.
            </p>

            <Select label="Vaccine" value={vax.vaccineTypeId}
              onChange={(e) => setVax((v) => ({ ...v, vaccineTypeId: e.target.value }))}>
              <option value="">Not now</option>
              {vaxTypes.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </Select>
            {errors.vaccineTypeId && (
              <p className="mt-1.5 text-sm text-pw-danger">{errors.vaccineTypeId}</p>
            )}

            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <Input label="Date given" type="date" value={vax.administeredOn}
                onChange={(e) => setVax((v) => ({ ...v, administeredOn: e.target.value }))}
                error={errors.administeredOn} />
              <Input label="Vet or clinic" placeholder="Dr Smith, Bay Vets"
                value={vax.administeredBy}
                onChange={(e) => setVax((v) => ({ ...v, administeredBy: e.target.value }))}
                error={errors.administeredBy} />
            </div>

            <FileDrop
              label="Certificate"
              className="mt-4"
              accept=".pdf,.jpg,.jpeg,.png,.webp"
              hint="PDF, JPG, PNG or WebP."
              value={cert}
              onChange={setCert}
            />
          </div>
        )}

        <div>
          <p className="text-sm font-medium text-pw-ink">Care instructions</p>
          <p className="mb-3 mt-1 text-sm text-pw-muted">
            All optional. Anything you leave blank is simply not recorded.
          </p>
          <div className="space-y-4">
            {CARE.map((c) => (
              <Textarea key={c.key} label={c.label} rows={2} placeholder={c.placeholder}
                value={care[c.key] ?? ""}
                onChange={(e) => setCare((x) => ({ ...x, [c.key]: e.target.value }))} />
            ))}
          </div>
        </div>
      </form>
    </Modal>
  );
}
