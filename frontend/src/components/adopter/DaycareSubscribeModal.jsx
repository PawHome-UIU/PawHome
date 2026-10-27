import { useState } from "react";
import { FiClock, FiCalendar } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { Select } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The second half of signing up for daycare. The quiz is the first half.
 *
 * TWO STEPS FOR THE REASON BOOKING BOARDING IS TWO STEPS. The quiz is the
 * BOARDING half of the shared question bank -- editable by an admin, answered
 * into quiz_attempts -- and this is the subscription's own columns. Collapsing
 * them would leave the pet and the start date looking like quiz answers when
 * they are neither.
 *
 * THE ATTEMPT IS CARRIED IN, not taken here: fk_sub_attempt is composite, so
 * the id handed over can only ever be attached as a BOARDING attempt, and the
 * server checks it belongs to the caller and that it PASSED before writing
 * anything.
 *
 * THE PRICE IS SHOWN BUT NEVER SENT. daycare_subscriptions.monthly_price is
 * copied from the shelter server-side; a price the browser posts is a customer
 * setting their own bill. What is rendered here is the quote, so the number on
 * screen is the number that gets stored.
 */
const today = () => new Date().toISOString().slice(0, 10);

export default function DaycareSubscribeModal({
  open, onClose, onDone, shelter, slot, pets, attemptId,
}) {
  const [petId, setPetId] = useState(pets?.length === 1 ? String(pets[0].id) : "");
  const [startDate, setStartDate] = useState(today());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  if (!open || !shelter || !slot) return null;

  const submit = async () => {
    setError("");
    if (!petId) { setError("Choose which pet this is for."); return; }
    setBusy(true);
    try {
      const { data } = await api.post("/daycare/subscriptions", {
        petId: Number(petId),
        shelterId: shelter.id,
        slot: slot.slot,
        quizAttemptId: attemptId,
        startDate,
      });
      onDone?.(data);
    } catch (err) {
      // The server refuses for reasons the customer can act on -- the slot
      // filled while this was open, the pet already holds it, the quiz was
      // failed -- so the sentence it sends is shown, not a generic one.
      setError(apiError(err, "Could not start that subscription."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={busy ? undefined : onClose}
      title="Start a daycare subscription"
      subtitle={`${shelter.name} — ${slot.label}`}
      footer={
        <div className="flex justify-end gap-3">
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button onClick={submit} disabled={busy}>
            {busy ? "Starting…" : "Start subscription"}
          </Button>
        </div>
      }
    >
      <div className="space-y-5">
        <div className="rounded-card bg-pw-box px-5 py-4">
          <div className="flex items-center justify-between gap-4">
            <span className="flex items-center gap-2 text-sm font-medium text-pw-ink">
              <FiClock className="h-4 w-4 text-pw-button" /> {slot.label}
            </span>
            <span className="text-lg font-semibold text-pw-ink">
              ৳{Number(shelter.monthlyPrice).toLocaleString()}
              <span className="ml-1 text-sm font-normal text-pw-muted">/ month</span>
            </span>
          </div>
          <p className="mt-2 text-sm text-pw-body">
            {slot.free} of {slot.capacity} places left. Billed once a month;
            you can cancel any time and keep the days you have paid for.
          </p>
        </div>

        <Select
          label="Which pet?"
          required
          value={petId}
          onChange={(e) => setPetId(e.target.value)}
        >
          <option value="">Choose a pet…</option>
          {pets.map((p) => (
            <option key={p.id} value={p.id}>{p.name} — {p.breed}</option>
          ))}
        </Select>

        <div>
          <label htmlFor="dc-start" className="mb-1.5 block text-sm font-medium text-pw-ink">
            Starting from
          </label>
          <div className="relative">
            <FiCalendar className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-pw-muted" />
            <input
              id="dc-start"
              type="date"
              // The server refuses a past start date; matching it here means
              // the customer is told before the round trip rather than after.
              min={today()}
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="h-11 w-full rounded-card border border-pw-stroke/60 bg-pw-input pl-11 pr-5
                         text-[15px] text-pw-ink focus:border-pw-button focus:outline-none
                         focus:ring-2 focus:ring-pw-button/25"
            />
          </div>
        </div>

        {error && (
          <p role="alert" className="rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}
      </div>
    </Modal>
  );
}
