import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { FiBell, FiCheck } from "react-icons/fi";
import useNotifications from "../../hooks/useNotifications";

/**
 * The bell.
 *
 * IT USED TO BE A HARD-CODED DOT. The markup carried a purple circle with no
 * state behind it, so every user in every portal had one unread notification
 * for ever -- and clicking it did nothing, because there was no inbox. The
 * `notifications` table has been in V1 since the beginning with six types and
 * a read_at column commented "drives the bell's dot"; nothing wrote to it and
 * nothing read from it.
 *
 * THE COUNT IS THE SERVER'S, NOT items.length. The dropdown fetches the most
 * recent twenty; the badge counts every unread row. Showing "20" beside
 * twenty-three unread would be wrong in the one place people trust a number.
 */
const MAX_BADGE = 99;

const when = (iso) => {
  if (!iso) return "";
  const then = new Date(iso);
  const mins = Math.round((Date.now() - then.getTime()) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  if (mins < 60 * 24) return `${Math.round(mins / 60)}h ago`;
  return then.toLocaleDateString(undefined, { month: "short", day: "numeric" });
};

export default function NotificationBell({ signedIn }) {
  const { unread, items, markAllRead, markRead } = useNotifications(signedIn);
  const [open, setOpen] = useState(false);
  const wrapRef = useRef(null);
  const navigate = useNavigate();

  // pointerdown, not click: a click fires after mouseup, so a drag that starts
  // inside the panel and ends outside would close it. Same reasoning as
  // Dropdown, which had this bug in three copies before it was one component.
  useEffect(() => {
    if (!open) return undefined;
    const away = (e) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false);
    };
    const esc = (e) => e.key === "Escape" && setOpen(false);
    document.addEventListener("pointerdown", away);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("pointerdown", away);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);

  if (!signedIn) return null;

  function openItem(n) {
    setOpen(false);
    if (!n.read) markRead(n.id);
    if (n.linkUrl) navigate(n.linkUrl);
  }

  return (
    // flex, not a plain block. The button inside is inline-level, so a block
    // wrapper builds a line box around it -- 27px tall against the 20px icon --
    // and the flex row centres that taller box, leaving the bell sitting three
    // pixels above the gear beside it. Blockifying the button removes the line
    // box and the two icons line up exactly.
    <div ref={wrapRef} className="relative flex items-center">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
        aria-expanded={open}
        className="relative text-pw-body hover:text-pw-button"
      >
        <FiBell className="h-5 w-5" />
        {/* Only when there is something. The old dot was permanent, which
            taught people to ignore it -- the opposite of what a badge is for. */}
        {unread > 0 && (
          <span className="absolute -right-1.5 -top-1.5 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-pw-button px-1 text-[10px] font-semibold leading-none text-white">
            {unread > MAX_BADGE ? `${MAX_BADGE}+` : unread}
          </span>
        )}
      </button>

      {open && (
        <div role="dialog" aria-label="Notifications"
          className="absolute right-0 top-9 z-50 w-[340px] overflow-hidden rounded-card border border-pw-stroke/50 bg-white shadow-lift">
          <div className="flex items-center justify-between gap-3 bg-pw-sidebar px-4 py-3">
            <p className="text-sm font-semibold text-white">Notifications</p>
            {unread > 0 && (
              <button type="button" onClick={markAllRead}
                className="inline-flex items-center gap-1 text-xs font-medium text-white/90 hover:text-white">
                <FiCheck className="h-3 w-3" /> Mark all read
              </button>
            )}
          </div>

          {items.length === 0 ? (
            <p className="px-4 py-10 text-center text-sm text-pw-muted">
              Nothing yet.
            </p>
          ) : (
            <ul className="max-h-[380px] divide-y divide-pw-stroke/30 overflow-y-auto">
              {items.map((n) => (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => openItem(n)}
                    className={`w-full px-4 py-3 text-left transition-colors hover:bg-pw-tint ${
                      n.read ? "" : "bg-pw-input/50"}`}
                  >
                    <div className="flex items-start gap-2.5">
                      {/* The unread marker is a dot beside the row, not a
                          colour on the text: colour alone is not a signal
                          everyone can see. */}
                      <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${
                        n.read ? "bg-transparent" : "bg-pw-button"}`} />
                      <div className="min-w-0 flex-1">
                        <p className={`text-sm leading-snug ${
                          n.read ? "text-pw-body" : "font-semibold text-pw-ink"}`}>
                          {n.title}
                        </p>
                        {n.body && (
                          <p className="mt-0.5 line-clamp-2 text-xs leading-relaxed text-pw-muted">
                            {n.body}
                          </p>
                        )}
                        <p className="mt-1 text-[11px] text-pw-muted">{when(n.createdAt)}</p>
                      </div>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
