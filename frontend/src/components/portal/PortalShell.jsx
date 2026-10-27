import { useState } from "react";
import { Link, NavLink, useNavigate } from "react-router-dom";
import {
  FiSearch, FiSettings, FiLogOut, FiLifeBuoy, FiMenu, FiX,
} from "react-icons/fi";
import Logo from "../brand/Logo";
import NotificationBell from "./NotificationBell";
import { useAuth } from "../../context/AuthContext";
import { API_BASE } from "../../lib/config";

/**
 * Chrome shared by the role portals: sidebar, topbar, page header.
 *
 * The convention here is one layout component per role, and that still holds --
 * AdminLayout and StaffLayout are separate files, each owning its own nav list
 * and its own user. What they do NOT own is a second copy of the sidebar
 * markup, because two copies drift: someone fixes the mobile drawer in one and
 * not the other, and only one role gets the fix.
 *
 * A role's links are passed in, never rendered-and-hidden. An admin's nav is
 * not in the staff bundle's tree at all.
 */
export default function PortalShell({
  nav,
  roleLabel,
  user,
  search = "Search…",
  /* Opt-in: pass both and the topbar box filters the page. Left off, it
     stays the decorative input it has always been -- a shared shell that
     silently starts filtering other portals is how an unrelated page
     begins hiding rows. */
  searchValue,
  onSearchChange,
  title,
  subtitle,
  actions,
  children,
}) {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const { logout, user: authUser } = useAuth();

  // The signed-in person. Every route that renders a portal is behind
  // RequireRole, so authUser is present in the running application; the `user`
  // prop survives only so the shell can be rendered in isolation, and the
  // optional chaining below is what keeps that harmless.
  //
  // THE LAYOUTS USED TO PASS A FAKE NAME HERE -- "Sarah Johnson", "Admin
  // Alex", "Sarah Jenkins", left over from the design-only build. They were
  // shadowed by a real session and therefore invisible, which is exactly why
  // they survived three increments: a placeholder that never shows is a
  // placeholder nobody deletes, until the day a null session shows it to a
  // customer.
  const shown = authUser
    ? { name: authUser.fullName, role: authUser.role, avatarPath: authUser.avatarPath }
    : user;
  const initial = shown?.name?.trim()?.[0] ?? "?";

  const sidebar = (
    <div className="flex h-full flex-col bg-pw-sidebar px-4 py-6 text-white">
      <div className="px-2">
        <Logo tone="white" to={nav[0].to} />
        <p className="mt-1 pl-8 text-[10px] font-semibold tracking-[0.18em] text-white/70">
          {roleLabel}
        </p>
      </div>

      <nav className="mt-9 flex-1">
        <ul className="space-y-1.5">
          {nav.map(({ to, label, icon: Icon, end }) => (
            <li key={to}>
              <NavLink
                to={to}
                end={end}
                onClick={() => setOpen(false)}
                className={({ isActive }) =>
                  [
                    "flex items-center gap-3 rounded-2xl px-4 py-3 text-[15px] transition-colors",
                    isActive
                      ? "bg-white/95 font-medium text-pw-button shadow-card"
                      : "text-white/90 hover:bg-white/15",
                  ].join(" ")
                }
              >
                <Icon className="h-[18px] w-[18px]" />
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <ul className="space-y-1.5 border-t border-white/20 pt-4">
        <li>
          <button type="button" className="flex w-full items-center gap-3 rounded-2xl px-4 py-3 text-[15px] text-white/90 hover:bg-white/15">
            <FiLifeBuoy className="h-[18px] w-[18px]" /> Help Center
          </button>
        </li>
        <li>
          <button
            type="button"
            onClick={() => {
              // Through the context, so ProtectedRoute re-evaluates at once.
              // Clearing storage alone leaves the guard holding a stale user
              // until the next full page load.
              logout();
              navigate("/login", { replace: true });
            }}
            className="flex w-full items-center gap-3 rounded-2xl px-4 py-3 text-[15px] text-white/90 hover:bg-white/15"
          >
            <FiLogOut className="h-[18px] w-[18px]" /> Logout
          </button>
        </li>
      </ul>
    </div>
  );

  return (
    <div className="min-h-screen bg-pw-tint">
      {/* Fixed, not sticky: the sidebar must not scroll away on a long page,
          and position:sticky inside a flex row fights the page's scroll. */}
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-[264px] lg:block">{sidebar}</aside>

      {open && (
        <>
          <div className="fixed inset-0 z-40 bg-pw-ink/40 lg:hidden" onClick={() => setOpen(false)} />
          <aside className="fixed inset-y-0 left-0 z-50 w-[264px] lg:hidden">{sidebar}</aside>
        </>
      )}

      <div className="lg:pl-[264px]">
        <header className="sticky top-0 z-30 border-b border-pw-stroke/40 bg-pw-tint/90 backdrop-blur">
          <div className="flex h-16 items-center gap-4 px-5 sm:px-8">
            <button type="button" onClick={() => setOpen((v) => !v)}
              className="text-pw-ink lg:hidden" aria-label="Toggle navigation">
              {open ? <FiX className="h-6 w-6" /> : <FiMenu className="h-6 w-6" />}
            </button>

            {/* RENDERED ONLY WHEN THE PAGE WIRES IT. It used to be drawn on
                every screen and connected on three, so on all the others it
                accepted typing and ignored it -- and on the two adopter pages
                that have their own search box it was a second, dead one right
                above the working one. A control that does nothing is worse
                than an absent control: the absent one asks no questions. */}
            {onSearchChange ? (
              <div className="relative hidden max-w-md flex-1 sm:block">
                <FiSearch className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-pw-muted" />
                <input type="search" placeholder={search} aria-label={search}
                  value={searchValue ?? ""}
                  onChange={(e) => onSearchChange(e.target.value)}
                  className="h-10 w-full rounded-pill bg-pw-input pl-11 pr-4 text-sm text-pw-ink placeholder:text-pw-muted focus:outline-none focus:ring-2 focus:ring-pw-button/25" />
              </div>
            ) : <div className="hidden flex-1 sm:block" />}

            <div className="ml-auto flex items-center gap-3">
              <NotificationBell signedIn={!!authUser} />
              {/* The gear is per-portal: nav[0].to is "/admin", "/staff" or
                  "/app", and SettingsPage picks its chrome from that prefix. */}
              <Link
                to={`${nav[0].to.replace(/\/$/, "")}/settings`}
                className="text-pw-body hover:text-pw-button"
                aria-label="Settings"
              >
                <FiSettings className="h-5 w-5" />
              </Link>
              <div className="flex items-center gap-2.5 border-l border-pw-stroke/50 pl-3">
                <div className="hidden text-right sm:block">
                  <p className="text-sm font-medium leading-tight text-pw-ink">{shown?.name}</p>
                  <p className="text-[10px] tracking-wider text-pw-muted">{shown?.role}</p>
                </div>
                {/* The picture Settings uploads, or the initial. Without this
                    branch somebody could change their avatar and the header
                    would keep showing a letter -- which reads as the upload
                    having failed. */}
                <span className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full bg-pw-sidebar text-sm font-semibold text-white">
                  {shown?.avatarPath
                    ? <img src={`${API_BASE}/files/${shown?.avatarPath}`} alt=""
                        className="h-full w-full object-cover" />
                    : initial}
                </span>
              </div>
            </div>
          </div>
        </header>

        <main className="px-5 py-8 sm:px-8">
          {(title || actions) && (
            <div className="mb-8 flex flex-wrap items-start justify-between gap-4">
              <div>
                {title && <h1 className="text-3xl font-semibold tracking-tight">{title}</h1>}
                {subtitle && <p className="mt-1.5 text-[15px] text-pw-body">{subtitle}</p>}
              </div>
              {actions && <div className="flex flex-wrap gap-3">{actions}</div>}
            </div>
          )}
          {children}
        </main>
      </div>
    </div>
  );
}
