import { useState } from "react";
import { FiInfo } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { Select, Textarea } from "../ui/Field";

/**
 * Decline a boarding request.
 *
 * Shaped like DeclineApplicationModal on purpose -- an owner turned down for
 * boarding deserves the same courtesy as one turned down for adoption, and two
 * decline dialogs that behave differently is how one of them ends up worse.
 *
 * Unlike the adoption decline, the schema does not force a reason here. It is
 * still required by this form: the difference is that a declined boarding is
 * usually a capacity problem the owner can work around by moving their dates,
 * and "no suites free that week" is the sentence that lets them.
 */
const REASONS = [
  "No suites free for those dates",
  "Pet's vaccinations are not up to date",
  "Care needs beyond what this shelter can provide",
  "Requested dates are too far ahead",
  "Owner withdrew the request",
  "Other",
];

export default function DeclineBoardingModal({ open, onClose, request, onDecline, busy = false }) {
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState("");
  const [touched, setTouched] = useState(false);

  const noReason = !reason;
  const tooShort = message.trim().length < 10;
  const blocked = noReason || tooShort;

  function submit(e) {
    e.preventDefault();
    setTouched(true);
    if (blocked) return;
    // The parent posts and closes. Resetting here would empty the form while
    // the request is still in flight, so a failure would leave the person
    // looking at a blank dialog with nothing to retry.
    onDecline?.({
      id: request?.id,
      ownerName: request?.ownerName,
      reason,
      message: message.trim(),
    });
  }

  function reset() {
    setReason("");
    setMessage("");
    setTouched(false);
  }

  function close() {
    reset();
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Decline Boarding Request"
      subtitle={
        request
          ? `${request.petName} · ${request.nights} night${request.nights === 1 ? "" : "s"}`
          : "Let the owner know why."
      }
      size="md"
    >
      <form onSubmit={submit} noValidate>
        <Select
          label="Reason"
          required
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          onBlur={() => setTouched(true)}
          aria-invalid={touched && noReason}
        >
          <option value="">Select a reason…</option>
          {REASONS.map((r) => <option key={r} value={r}>{r}</option>)}
        </Select>
        {touched && noReason && (
          <p className="mt-1.5 text-sm text-pw-danger">Pick the reason you are declining.</p>
        )}

        <Textarea
          label="Message to the owner"
          className="mt-5"
          required
          rows={4}
          placeholder="If other dates would work, say so here — most declines are a capacity problem, not a no."
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          onBlur={() => setTouched(true)}
          aria-invalid={touched && tooShort}
        />
        {touched && tooShort && (
          <p className="mt-1.5 text-sm text-pw-danger">
            Please write at least a sentence — this is what the owner will read.
          </p>
        )}

        <div className="mt-6 flex gap-3 rounded-card bg-pw-input p-4">
          <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
          <p className="text-sm leading-relaxed text-pw-body">
            Declining frees these dates immediately, so another request for the
            same suite can be accepted.
          </p>
        </div>

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={close} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={blocked || busy} className="!bg-pw-danger hover:!bg-rose-700">
            {busy ? "Declining…" : "Decline Request"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
