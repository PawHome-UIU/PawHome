import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { FiMoreVertical } from "react-icons/fi";

const WIDTH = 232;

/**
 * The "⋮" menu on a table row.
 *
 * WHY A PORTAL AND NOT AN ABSOLUTELY POSITIONED DIV. DataTable wraps its table
 * in `overflow-x-auto` so wide tables scroll instead of the page. A CSS
 * overflow value other than `visible` on one axis forces the other axis to
 * clip too, so a menu positioned inside that container is cut off at the last
 * row -- and the last row is exactly where a row menu opens. Rendering into
 * document.body with fixed coordinates sidesteps the whole question.
 *
 * The coordinates are taken in the click handler, not in an effect: an effect
 * that measures and then sets state runs after paint, which is one frame of
 * the menu drawn in the wrong place.
 *
 * IT CLOSES ON SCROLL. Fixed coordinates do not follow a scrolling page, so a
 * menu left open would drift away from its own row. Closing is honest;
 * tracking the row would mean re-measuring on every scroll event.
 */
export default function ActionMenu({ items, label = "Actions", disabled = false }) {
  const [rect, setRect] = useState(null);
  const btnRef = useRef(null);
  const menuRef = useRef(null);
  const open = rect !== null;

  useEffect(() => {
    if (!open) return undefined;

    const close = () => setRect(null);
    const onPointerDown = (e) => {
      if (!menuRef.current?.contains(e.target) && !btnRef.current?.contains(e.target)) close();
    };
    const onKey = (e) => {
      if (e.key === "Escape") {
        close();
        btnRef.current?.focus();
      }
    };

    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKey);
    window.addEventListener("scroll", close, true);   // true: catches the table's own scroller
    window.addEventListener("resize", close);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
    };
  }, [open]);

  const shown = items.filter(Boolean);
  if (shown.length === 0) return null;

  function toggle() {
    setRect((r) => (r ? null : btnRef.current.getBoundingClientRect()));
  }

  // Roughly how tall the menu will be, used only to decide up or down. Being a
  // few pixels out just means it opens downward when it could have gone up.
  const height = shown.length * 40 + 12;
  const below = rect && rect.bottom + height + 12 < window.innerHeight;

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        onClick={toggle}
        disabled={disabled}
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        className={`flex h-8 w-8 items-center justify-center rounded-full text-pw-muted transition-colors hover:bg-pw-input hover:text-pw-ink disabled:opacity-40 ${open ? "bg-pw-input text-pw-ink" : ""}`}
      >
        <FiMoreVertical className="h-4 w-4" />
      </button>

      {open && createPortal(
        <div
          ref={menuRef}
          role="menu"
          style={{
            position: "fixed",
            width: WIDTH,
            // Kept on screen: a menu on a narrow viewport would otherwise sit
            // half outside the left edge with no way to scroll to it.
            left: Math.max(8, Math.min(rect.right - WIDTH, window.innerWidth - WIDTH - 8)),
            top: below ? rect.bottom + 6 : rect.top - height - 6,
          }}
          className="z-50 overflow-hidden rounded-card border border-pw-stroke/50 bg-white py-1.5 shadow-lg shadow-pw-ink/10"
        >
          {shown.map(({ label: text, icon: Icon, onClick, tone }) => (
            <button
              key={text}
              type="button"
              role="menuitem"
              onClick={() => { setRect(null); onClick(); }}
              className={`flex w-full items-center gap-2.5 px-4 py-2.5 text-left text-sm transition-colors hover:bg-pw-input ${
                tone === "danger" ? "text-pw-danger" : "text-pw-body"}`}
            >
              {Icon && <Icon className="h-4 w-4 shrink-0" />}
              {text}
            </button>
          ))}
        </div>,
        document.body,
      )}
    </>
  );
}
