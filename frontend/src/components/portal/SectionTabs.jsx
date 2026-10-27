import { NavLink } from "react-router-dom";

/**
 * A row of tabs that are LINKS, not buttons.
 *
 * WHY NOT components/ui/Tabs. That one is a controlled button row -- it holds
 * the active tab in the parent's state, which is right for the Quiz Questions
 * page where all three panels are one screen's worth of already-loaded data.
 * These three are separate pages with separate fetches, so making them buttons
 * would mean one component owning three unrelated loads, and the URL would stop
 * saying where you are: no deep link, no back button, no refresh-in-place.
 *
 * NavLink gives the active state from the route itself, so there is no second
 * copy of "which tab am I on" to get out of step with the address bar.
 *
 * `end` matters on every entry. Without it /app/boarding would also count as
 * active while you are on /app/boarding/12, and two tabs would look selected.
 *
 * The styling deliberately matches ui/Tabs -- same underline, same colours. Two
 * tab rows in one app that look different read as two different controls.
 *
 * The tab LISTS live in lib/navTabs.js, not here: a file exporting both a
 * component and a constant breaks Fast Refresh.
 */
export default function SectionTabs({ tabs, className = "" }) {
  return (
    <div
      role="tablist"
      className={`mb-6 flex gap-7 border-b border-pw-stroke/50 ${className}`}
    >
      {tabs.map((t) => (
        <NavLink
          key={t.to}
          to={t.to}
          end
          role="tab"
          className={({ isActive }) =>
            [
              "-mb-px border-b-2 pb-3 text-[15px] transition-colors",
              isActive
                ? "border-pw-button font-medium text-pw-button"
                : "border-transparent text-pw-muted hover:text-pw-body",
            ].join(" ")
          }
        >
          {t.label}
        </NavLink>
      ))}
    </div>
  );
}

