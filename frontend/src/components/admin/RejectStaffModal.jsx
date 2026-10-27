import { useState } from "react";
import { FiInfo } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { Textarea } from "../ui/Field";

/**
 * Turn down a staff sign-up.
 *
 * The reason is required, and the argument is the same one as declining an
 * adoption: a refusal with no explanation is the thing people complain about,
 * and one sentence costs nothing. The server enforces a 10-character minimum
 * too, so a determined admin cannot get past it by typing "no".
 */
export default function RejectStaffModal({ open, user, onClose, onReject }) {
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);

  const tooShort = reason.trim().length < 10;

  function submit(e) {
    e.preventDefault();
    setTouched(true);
    if (tooShort) return;
    onReject?.({ reason: reason.trim() });
    setReason("");
    setTouched(false);
  }

  function close() {
    setReason("");
    setTouched(false);
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Decline this request"
      subtitle={user ? `${user.fullName} · ${user.shelterName ?? "no shelter"}` : undefined}
      size="md"
    >
      <form onSubmit={submit} noValidate>
        <Textarea
          label="Why are you declining?"
          required
          rows={4}
          placeholder="e.g. We couldn't match this name to anyone on the shelter's staff list."
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          onBlur={() => setTouched(true)}
          aria-invalid={touched && tooShort}
        />
        {touched && tooShort && (
          <p className="mt-1.5 text-sm text-pw-danger">
            Please write at least a sentence.
          </p>
        )}

        <div className="mt-6 flex gap-3 rounded-card bg-pw-input p-4">
          <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
          <p className="text-sm leading-relaxed text-pw-body">
            The account is kept rather than deleted, so the same person cannot
            quietly sign up again and reappear as a fresh request.
          </p>
        </div>

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={close}>Cancel</Button>
          <Button type="submit" disabled={tooShort}
            className="!bg-pw-danger hover:!bg-rose-700">
            Decline request
          </Button>
        </div>
      </form>
    </Modal>
  );
}
