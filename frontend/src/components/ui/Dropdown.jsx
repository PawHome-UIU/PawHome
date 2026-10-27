import { useEffect, useId, useRef, useState } from "react";
import { FiChevronDown, FiCheck } from "react-icons/fi";

/**
 * The one dropdown in this application.
 *
 * There were three near-copies of this before -- the filter pills on the public
 * browse grid, the filter pills in the adopter portal, and the status/species
 * pickers on Manage Pets -- plus several bare <select> elements wearing the
 * browser's own chrome in the middle of a designed toolbar.
 *
 * None of the three copies closed when you clicked somewhere else on the page.
 * That is the sort of bug you fix once and get wrong in the other two, which is
 * the argument for this file existing at all.
 *
 * WHAT MAKES IT CLOSE
 *  - pointerdown outside the wrapper (pointerdown, not click: a click fires
 *    after mouseup, so a drag that starts inside and ends outside would leave
 *    it open);
 *  - Escape, which also returns focus to the trigger;
 *  - choosing an option;
 *  - the trigger itself, toggling.
 *
 * Rendered as a real menu button rather than a styled <select> because the
 * design's list has a check mark, a hover tint and an "Any …" reset row -- none
 * of which a native <option> can carry. Where the native control WOULD do, use
 * Select from ui/Field instead; it is cheaper and it gets the mobile OS picker.
 */
export default function Dropdown({
  label,
  options,
  value,
  onChange,
  /** Label for the reset row. Omit for a dropdown with no empty state. */
  anyLabel,
  /** "pill" for filter chips, "bar" for toolbar controls with a visible label. */
  variant = "pill",
  align = "left",
  className = "",
  buttonClassName = "",
}) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef(null);
  const btnRef = useRef(null);
  const id = useId();

  useEffect(() => {
    if (!open) return undefined;

    const onPointerDown = (e) => {
      if (!wrapRef.current?.contains(e.target)) setOpen(false);
    };
    const onKey = (e) => {
      if (e.key === "Escape") {
        setOpen(false);
        btnRef.current?.focus();
      }
    };

    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const selected = options.find((o) => (o.value ?? o) === value);
  const selectedLabel = selected ? selected.label ?? selected : null;

  const trigger =
    variant === "pill"
      ? [
          "inline-flex items-center gap-1.5 rounded-pill border px-4 py-2 text-sm transition-colors",
          value
            ? "border-pw-button bg-pw-input text-pw-button"
            : "border-pw-stroke/60 bg-white text-pw-body hover:border-pw-stroke",
        ].join(" ")
      : "inline-flex items-center justify-between gap-2 rounded-pill border border-pw-stroke/60 bg-white px-4 py-2 text-sm text-pw-ink transition-colors hover:border-pw-stroke";

  return (
    <div ref={wrapRef} className={`relative ${className}`}>
      <button
        ref={btnRef}
        type="button"
        id={id}
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-haspopup="listbox"
        className={`${trigger} ${buttonClassName}`}
      >
        <span className="truncate">{selectedLabel || label}</span>
        <FiChevronDown
          className={`h-4 w-4 shrink-0 text-pw-muted transition-transform ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open && (
        <ul
          role="listbox"
          aria-labelledby={id}
          className={[
            "absolute top-full z-30 mt-2 min-w-full max-w-[260px] overflow-hidden rounded-2xl",
            "border border-pw-stroke/50 bg-white py-1 shadow-lift",
            align === "right" ? "right-0" : "left-0",
          ].join(" ")}
        >
          {anyLabel && (
            <li>
              <button
                type="button"
                role="option"
                aria-selected={!value}
                onClick={() => { onChange(null); setOpen(false); }}
                className="block w-full whitespace-nowrap px-4 py-2 text-left text-sm text-pw-muted hover:bg-pw-input"
              >
                {anyLabel}
              </button>
            </li>
          )}

          {options.map((o) => {
            const val = o.value ?? o;
            const text = o.label ?? o;
            const on = val === value;
            return (
              <li key={val}>
                <button
                  type="button"
                  role="option"
                  aria-selected={on}
                  onClick={() => { onChange(val); setOpen(false); }}
                  className={[
                    "flex w-full items-center justify-between gap-3 whitespace-nowrap px-4 py-2 text-left text-sm hover:bg-pw-input",
                    on ? "font-medium text-pw-button" : "text-pw-body",
                  ].join(" ")}
                >
                  {text}
                  {on && <FiCheck className="h-3.5 w-3.5 shrink-0" />}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
