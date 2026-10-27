import { useState } from "react";
import { FiInfo } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { Select, Textarea } from "../ui/Field";
/**
 * The six values of adoption_applications.decline_reason_code, with the label
 * a reviewer reads. The CODE is what is stored and counted; the label is only
 * for the dropdown, so renaming one here changes nothing in the database.
 *
 * PET_ALREADY_MATCHED is absent on purpose: it is written by the server when
 * an application is approved and every other one for that pet is closed. A
 * reviewer choosing it by hand would be saying the pet is gone without any of
 * that having happened.
 */
const DECLINE_REASONS = [
  ["INCOMPLETE_INFO", "Incomplete information"],
  ["HOUSING_UNSUITABLE", "Housing unsuitable"],
  ["INSUFFICIENT_EXPERIENCE", "Insufficient experience"],
  ["FAILED_ELIGIBILITY", "Failed eligibility"],
  ["OTHER", "Other"],
];

/**
 * Decline Application.
 *
 * BOTH fields are mandatory, and not as a matter of taste. V1__init.sql:
 *
 *   CONSTRAINT ck_apps_decline CHECK (
 *       status <> 'REJECTED' OR
 *       (decline_reason_code IS NOT NULL AND decline_message IS NOT NULL))
 *
 * A decline missing either one cannot be written at all, so the button stays
 * disabled until both are present. Failing here, next to the empty field, beats
 * a 500 from the API with the form already dismissed.
 *
 * The two fields are not redundant: the reason is a code, for the analytics
 * page to group by; the message is prose, and it is what the applicant reads.
 */
export default function DeclineApplicationModal({
  open, onClose, application, onDecline, busy = false, error = "",
}) {
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
    // Field names match DeclineRequest. Nothing is cleared here: the parent
    // owns the request and this form has to still hold what was typed if the
    // server refuses it.
    onDecline?.({ reasonCode: reason, message: message.trim() });
  }

  function reset() {
    setReason("");
    setMessage("");
    setTouched(false);
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Decline Application"
      subtitle={application
        ? `${application.applicantName} · ${application.petName}`
        : "Let the applicant know why this application was declined."}
      size="md"
    >
      <form onSubmit={submit} noValidate>
        {error && (
          <p role="alert" className="mb-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}

        <Select
          label="Reason"
          required
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          onBlur={() => setTouched(true)}
          aria-invalid={touched && noReason}
        >
          <option value="">Select a reason…</option>
          {DECLINE_REASONS.map(([code, label]) => (
            <option key={code} value={code}>{label}</option>
          ))}
        </Select>
        {touched && noReason && (
          <p className="mt-1.5 text-sm text-pw-danger">Pick the reason this was declined.</p>
        )}

        <Textarea
          label="Message to applicant"
          className="mt-5"
          required
          rows={5}
          placeholder="Add a short explanation…"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          onBlur={() => setTouched(true)}
          aria-invalid={touched && tooShort}
        />
        {touched && tooShort && (
          <p className="mt-1.5 text-sm text-pw-danger">
            Please write at least a sentence — this is what the applicant will read.
          </p>
        )}

        <div className="mt-6 flex gap-3 rounded-card bg-pw-input p-4">
          <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
          <p className="text-sm leading-relaxed text-pw-body">
            This reason will be shared with the applicant. Please be professional
            and constructive in your feedback.
          </p>
        </div>

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={close} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={blocked || busy} className="!bg-pw-danger hover:!bg-rose-700">
            {busy ? "Declining…" : "Decline Application"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
