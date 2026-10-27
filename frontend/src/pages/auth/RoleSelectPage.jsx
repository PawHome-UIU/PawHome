import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { FiShield, FiHome, FiHeart, FiEye, FiCheck, FiCheckCircle } from "react-icons/fi";
import Logo from "../../components/brand/Logo";
import Button from "../../components/ui/Button";

/**
 * "How do you want to join PawHome?"
 *
 * A separate screen before registration, per the design. The chosen role is
 * carried to /register in router state -- NOT in the URL, because a role is a
 * claim the backend must validate on the sign-up request anyway, and a
 * query string invites someone to try ?role=ADMIN by hand.
 */
const ROLES = [
  { key: "ADMIN", label: "Admin", icon: FiShield,
    blurb: "Full system control and oversight.",
    points: ["Manage all site users", "Access financial analytics"] },
  { key: "STAFF", label: "Shelter Staff", icon: FiHome,
    blurb: "Manage pets and applications.",
    points: ["Post pet profiles", "Review adoption requests"] },
  { key: "ADOPTER", label: "Adopter", icon: FiHeart,
    blurb: "Find and connect with pets.",
    points: ["Apply to adopt pets", "Save favorite pets"] },
  { key: "GUEST", label: "Guest", icon: FiEye,
    blurb: "Browse and explore listings.",
    points: ["Search all available pets", "View shelter locations"] },
];

export default function RoleSelectPage() {
  const [selected, setSelected] = useState("ADOPTER");
  const navigate = useNavigate();

  function onContinue() {
    // Guests never get an account -- send them straight to the catalogue.
    if (selected === "GUEST") return navigate("/pets");
    navigate("/register", { state: { role: selected } });
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-pw-tint to-pw-box">
      <header className="flex items-center justify-between px-6 py-5 sm:px-10">
        <Logo />
        <Button to="/login" variant="outline" size="sm">Log in</Button>
      </header>

      <main className="mx-auto max-w-3xl px-5 pb-20 pt-6 sm:px-8">
        <h1 className="text-center text-3xl font-semibold sm:text-4xl">
          How do you want to join PawHome?
        </h1>
        <p className="mt-3 text-center text-[15px] text-pw-body">
          Choose your role to get started — you'll sign up next.
        </p>

        <div className="mt-10 rounded-card bg-white p-6 shadow-card sm:p-8">
          <h2 className="text-center text-xl font-semibold">Select your role</h2>

          <div
            role="radiogroup"
            aria-label="Select your role"
            className="mt-6 grid gap-4 sm:grid-cols-2"
          >
            {ROLES.map(({ key, label, icon: Icon, blurb, points }) => {
              const active = selected === key;
              return (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  onClick={() => setSelected(key)}
                  className={[
                    "relative rounded-card border p-5 text-left transition-colors",
                    active
                      ? "border-pw-button bg-pw-input"
                      : "border-pw-stroke/60 bg-white hover:border-pw-stroke",
                  ].join(" ")}
                >
                  {active && (
                    <span className="absolute right-4 top-4 flex h-6 w-6 items-center justify-center rounded-full bg-pw-button text-white">
                      <FiCheck className="h-3.5 w-3.5" />
                    </span>
                  )}

                  <span className="flex items-center gap-3">
                    <span className="flex h-10 w-10 items-center justify-center rounded-full bg-pw-input text-pw-button">
                      <Icon className="h-5 w-5" />
                    </span>
                    <span className="text-lg font-medium text-pw-button">{label}</span>
                  </span>

                  <p className="mt-4 text-[15px] text-pw-ink">{blurb}</p>

                  <ul className="mt-3 space-y-1.5">
                    {points.map((p) => (
                      <li key={p} className="flex items-center gap-2 text-sm text-pw-body">
                        <FiCheckCircle className="h-3.5 w-3.5 shrink-0 text-pw-button" />
                        {p}
                      </li>
                    ))}
                  </ul>
                </button>
              );
            })}
          </div>

          <Button full size="lg" className="mt-7" onClick={onContinue}>
            {selected === "GUEST" ? "Continue as Guest" : "Continue to Sign up"}
          </Button>

          <p className="mt-3 text-center text-sm text-pw-muted">
            Your role is set when you sign up — you'll always log in as this role.
          </p>
        </div>

        <p className="mt-8 text-center text-[15px] text-pw-body">
          Already have an account?{" "}
          <Link to="/login" className="font-medium text-pw-button hover:underline">Log in</Link>
        </p>
      </main>
    </div>
  );
}
