import { useEffect } from "react";
import { FiCheckCircle, FiX } from "react-icons/fi";
import Button from "./Button";

/**
 * The confirmation dialog the design reuses after every create/submit action --
 * add a pet, add a shelter, add a question, log a vaccination, submit a
 * boarding request, submit an adoption application.
 *
 * Deliberately NOT built on Modal: this one has no purple header band, and its
 * whole job is the centred tick. Six near-identical copies of it is how the
 * confirmation for "Pet added" ends up saying something subtly different from
 * the one for "Shelter added".
 *
 * The design's stock copy is "Congratulations! / Your task has been completed
 * successfully." That is the fallback, not the goal -- pass `title` and
 * `message` naming what actually happened. "Your task" tells someone who
 * clicked Submit nothing they did not already know.
 */
export default function SuccessModal({
  open,
  onClose,
  title = "Congratulations!",
  message = "Your task has been completed successfully.",
  actionLabel = "Okay",
  onAction,
}) {
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => e.key === "Escape" && onClose?.();
    document.addEventListener("keydown", onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prev;
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="fixed inset-0 bg-pw-ink/45" onClick={onClose} aria-hidden="true" />

      <div
        role="alertdialog"
        aria-modal="true"
        aria-label={title}
        className="relative z-10 w-full max-w-md rounded-card bg-white px-8 py-10 text-center shadow-lift"
      >
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="absolute right-5 top-5 flex h-9 w-9 items-center justify-center rounded-full bg-pw-input text-pw-button hover:bg-pw-box"
        >
          <FiX className="h-4 w-4" />
        </button>

        <div className="relative mx-auto h-20 w-20">
          {/* Confetti dots from the design. Decorative, so hidden from
              assistive tech rather than described as three purple squares. */}
          <span aria-hidden="true" className="absolute -left-5 top-6 h-1.5 w-1.5 rounded-full bg-pw-stroke" />
          <span aria-hidden="true" className="absolute -top-1 left-1 h-1.5 w-1.5 rounded-full bg-pw-sidebar" />
          <span aria-hidden="true" className="absolute -right-4 top-3 h-1.5 w-1.5 rounded-sm bg-pw-button" />
          <span className="flex h-20 w-20 items-center justify-center rounded-full bg-pw-input text-pw-button">
            <FiCheckCircle className="h-9 w-9" />
          </span>
        </div>

        <h2 className="mt-6 text-2xl font-semibold tracking-tight">{title}</h2>
        <p className="mt-2.5 text-[15px] leading-relaxed text-pw-body">{message}</p>

        <Button full size="lg" className="mt-7" onClick={onAction || onClose}>
          {actionLabel}
        </Button>
      </div>
    </div>
  );
}
