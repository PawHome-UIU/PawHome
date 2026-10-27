import { useEffect } from "react";
import { FiX } from "react-icons/fi";

/**
 * Centred dialog with the design's purple header band.
 *
 * Escape closes it and the body stops scrolling while it is open -- both are
 * things people expect and nobody reports as a bug, they just find the app
 * annoying.
 */
export default function Modal({ open, onClose, title, subtitle, children, footer, size = "md" }) {
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

  const widths = { sm: "max-w-md", md: "max-w-lg", lg: "max-w-2xl", xl: "max-w-5xl" }[size];

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto p-4 sm:p-8">
      <div className="fixed inset-0 bg-pw-ink/45" onClick={onClose} aria-hidden="true" />

      <div role="dialog" aria-modal="true" aria-label={title}
        className={`relative z-10 my-auto w-full ${widths} overflow-hidden rounded-card bg-white shadow-lift`}>
        <div className="flex items-start justify-between gap-4 bg-pw-sidebar px-6 py-5">
          <div>
            <h2 className="text-xl font-semibold text-white">{title}</h2>
            {subtitle && <p className="mt-1 text-sm text-white/85">{subtitle}</p>}
          </div>
          <button type="button" onClick={onClose} aria-label="Close"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-white text-pw-button hover:bg-pw-input">
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="px-6 py-6">{children}</div>

        {footer && (
          <div className="flex justify-end gap-3 border-t border-pw-stroke/40 bg-pw-input/40 px-6 py-4">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
