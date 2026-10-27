import { useState } from "react";
import { NavLink } from "react-router-dom";
import { FiMenu, FiX } from "react-icons/fi";
import Logo from "../brand/Logo";
import Button from "../ui/Button";

const LINKS = [
  { to: "/", label: "Home", end: true },
  { to: "/pets", label: "Browse Pets" },
  { to: "/features", label: "Features" },
  { to: "/services", label: "Services" },
  { to: "/about", label: "About" },
];

export default function PublicNav() {
  const [open, setOpen] = useState(false);

  return (
    <header className="sticky top-0 z-50 border-b border-pw-stroke/40 bg-pw-tint/90 backdrop-blur">
      <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5 sm:px-8">
        <Logo />

        <ul className="hidden items-center gap-8 md:flex">
          {LINKS.map((l) => (
            <li key={l.label}>
              <NavLink
                to={l.to}
                end={l.end}
                className={({ isActive }) =>
                  [
                    "text-sm transition-colors",
                    isActive
                      ? "font-medium text-pw-button"
                      : "text-pw-body hover:text-pw-button",
                  ].join(" ")
                }
              >
                {l.label}
              </NavLink>
            </li>
          ))}
        </ul>

        <div className="hidden items-center gap-4 md:flex">
          <NavLink to="/login" className="text-sm text-pw-body hover:text-pw-button">
            Log in
          </NavLink>
          <Button to="/join" size="sm">Sign up</Button>
        </div>

        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="text-pw-ink md:hidden"
          aria-label={open ? "Close menu" : "Open menu"}
          aria-expanded={open}
        >
          {open ? <FiX className="h-6 w-6" /> : <FiMenu className="h-6 w-6" />}
        </button>
      </nav>

      {open && (
        <div className="border-t border-pw-stroke/40 bg-pw-tint px-5 py-4 md:hidden">
          <ul className="flex flex-col gap-3">
            {LINKS.map((l) => (
              <li key={l.label}>
                <NavLink to={l.to} end={l.end} onClick={() => setOpen(false)}
                  className="block py-1 text-pw-body hover:text-pw-button">
                  {l.label}
                </NavLink>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex items-center gap-3">
            <Button to="/login" variant="outline" size="sm" full>Log in</Button>
            <Button to="/join" size="sm" full>Sign up</Button>
          </div>
        </div>
      )}
    </header>
  );
}
