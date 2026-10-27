import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { FcGoogle } from "react-icons/fc";
import { FiShield, FiHome, FiHeart } from "react-icons/fi";
import AuthLayout from "../../components/layout/AuthLayout";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import Checkbox from "../../components/ui/Checkbox";
import { Select } from "../../components/ui/Field";
import Logo from "../../components/brand/Logo";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { passwordError, DESCRIPTION as PASSWORD_RULE } from "../../lib/passwordPolicy";
import PasswordChecklist from "../../components/auth/PasswordChecklist";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Only the three roles that get an account. GUEST is a browsing mode, not a
// row in users -- the role picker sends it straight to /pets instead.
const ROLES = [
  { key: "ADOPTER", label: "Adopter", icon: FiHeart },
  { key: "STAFF", label: "Shelter", icon: FiHome },
  { key: "ADMIN", label: "Admin", icon: FiShield },
];

export default function RegisterPage() {
  const navigate = useNavigate();
  const { state } = useLocation();

  const [form, setForm] = useState({
    fullName: "", email: "", password: "", confirm: "",
    role: state?.role && state.role !== "GUEST" ? state.role : "ADOPTER",
    shelterId: "",
    terms: false,
  });
  const [errors, setErrors] = useState({});
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);

  // The shelter list comes from the server, not from a hard-coded array.
  // It used to come from data/admin.js, whose ids matched nothing in the
  // database -- so every staff sign-up died on fk_users_shelter and the
  // account was never created. GET /shelters is deliberately open: this
  // runs before the person has a token.
  const [shelters, setShelters] = useState([]);
  const [sheltersError, setSheltersError] = useState("");

  useEffect(() => {
    let cancelled = false;
    api.get("/shelters")
      .then(({ data }) => { if (!cancelled) setShelters(data); })
      .catch((err) => {
        if (!cancelled) {
          // Say so rather than showing an empty dropdown. An empty <select>
          // reads as "no shelters exist", and the person retypes the form
          // instead of retrying.
          setSheltersError(apiError(err, "Could not load the shelter list."));
        }
      });
    return () => { cancelled = true; };
  }, []);


  const set = (k) => (e) => {
    const v = e.target.type === "checkbox" ? e.target.checked : e.target.value;
    setForm((f) => ({ ...f, [k]: v }));
    if (errors[k]) setErrors((x) => ({ ...x, [k]: undefined }));
  };

  function validate() {
    const n = {};
    if (!form.fullName.trim()) n.fullName = "Full name is required";
    if (!form.email) n.email = "Email address is required";
    else if (!EMAIL_RE.test(form.email)) n.email = "Invalid email address";
    // One shared rule set with the server -- see lib/passwordPolicy.js.
    const pw = passwordError(form.password);
    if (pw) n.password = pw;
    if (form.confirm !== form.password) n.confirm = "Passwords do not match";
    if (form.role === "STAFF" && !form.shelterId) {
      n.shelterId = "Choose the shelter you are joining";
    }
    if (!form.terms) n.terms = "Please accept the terms to continue";
    setErrors(n);
    return Object.keys(n).length === 0;
  }

  async function onSubmit(e) {
    e.preventDefault();
    setFormError("");
    if (!validate()) return;

    setBusy(true);
    try {
      await api.post("/auth/register", {
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        password: form.password,
        role: form.role,
        // ck_users_staff_shelter: a STAFF row must name a shelter and nobody
        // else may. Sending it unconditionally would make every adopter
        // sign-up a 400 ("Adopters do not belong to a shelter").
        ...(form.role === "STAFF" ? { shelterId: Number(form.shelterId) } : {}),
      });
      // A STAFF sign-up is created PENDING and cannot log in until an admin
      // approves it, so it must not be sent to a login form that will reject
      // it with no explanation.
      // NOT to /login. Nobody can sign in until they confirm their address,
      // so sending them to a form that will refuse them -- as this used to,
      // with "You can log in now" -- is a dead end.
      navigate("/check-email", {
        replace: true,
        state: { email: form.email.trim(), role: form.role },
      });
    } catch (err) {
      setFormError(apiError(err, "Could not create the account. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      side="right"
      title="Find a friend for life"
      tagline="Connecting loving families with pets in need of a forever home across the country."
    >
      <div className="text-center">
        <Logo />
        <h1 className="mt-7 text-2xl font-semibold">Create your account</h1>
        <p className="mt-1.5 text-[15px] text-pw-muted">
          Join our community and start your journey to find a furry friend.
        </p>
      </div>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-4">
        {formError && (
          <p role="alert"
            className="rounded-2xl border border-pw-danger/30 bg-pw-danger/5 px-4 py-3 text-sm text-pw-danger">
            {formError}
          </p>
        )}

        <Input label="Full name" autoComplete="name" placeholder="Jane Doe"
          value={form.fullName} onChange={set("fullName")} error={errors.fullName} />

        <Input label="Email address" type="email" autoComplete="email" placeholder="jane@example.com"
          value={form.email} onChange={set("email")} error={errors.email} />

        <div>
          <Input label="Password" type="password" autoComplete="new-password" placeholder="••••••••"
            value={form.password} onChange={set("password")} error={errors.password} />
          {/* The rules, ticking off as they are met. A strength bar reading
              "Fair" tells nobody what to change; "one uppercase letter" does.
              The bar also disagreed with the server, which is the rule that
              actually decides. */}
          <PasswordChecklist password={form.password} />

          {!form.password && (
            <p className="mt-2 text-xs text-pw-muted">{PASSWORD_RULE}</p>
          )}
        </div>

        <Input label="Confirm password" type="password" autoComplete="new-password" placeholder="••••••••"
          value={form.confirm} onChange={set("confirm")} error={errors.confirm} />

        <fieldset className="pt-1">
          <legend className="mb-2 text-sm font-medium text-pw-ink">I am an…</legend>
          <div className="grid grid-cols-3 gap-3">
            {ROLES.map(({ key, label, icon: Icon }) => {
              const active = form.role === key;
              return (
                <button key={key} type="button"
                  onClick={() => setForm((f) => ({ ...f, role: key }))}
                  aria-pressed={active}
                  className={[
                    "flex flex-col items-center gap-1.5 rounded-2xl border px-2 py-3 transition-colors",
                    active
                      ? "border-pw-button bg-pw-input text-pw-button"
                      : "border-pw-stroke/60 bg-white text-pw-body hover:border-pw-stroke",
                  ].join(" ")}
                >
                  <Icon className="h-5 w-5" />
                  <span className="text-sm font-medium">{label}</span>
                </button>
              );
            })}
          </div>
        </fieldset>

        {/* Only staff have a shelter, and they must have one. Shown
            conditionally so an adopter is never asked a question that would be
            rejected if they answered it. */}
        {form.role === "STAFF" && (
          <Select
            label="Which shelter are you joining?"
            required
            value={form.shelterId}
            onChange={set("shelterId")}
          >
            <option value="">
              {shelters.length ? "Select a shelter…" : "Loading shelters…"}
            </option>
            {shelters.map((sh) => (
              <option key={sh.id} value={sh.id}>
                {sh.city ? `${sh.name} — ${sh.city}, ${sh.state}` : sh.name}
              </option>
            ))}
          </Select>
        )}
        {form.role === "STAFF" && sheltersError && (
          <p className="-mt-2 text-sm text-pw-danger">{sheltersError}</p>
        )}
        {form.role === "STAFF" && !sheltersError && shelters.length === 0 && (
          <p className="-mt-2 text-sm text-pw-muted">
            No shelters are open for staff sign-up yet. Ask an administrator to
            add yours.
          </p>
        )}
        {errors.shelterId && (
          <p className="-mt-2 text-sm text-pw-danger">{errors.shelterId}</p>
        )}

        <p className="text-center">
          <Link to="/pets" className="text-sm font-medium text-pw-button hover:underline">
            Just browsing? Continue as Guest
          </Link>
        </p>

        <div>
          <Checkbox
            checked={form.terms}
            onChange={set("terms")}
            label={<>I agree to the Terms &amp; Privacy Policy</>}
          />
          {errors.terms && <p className="mt-1.5 text-sm text-pw-danger">{errors.terms}</p>}
        </div>

        <Button type="submit" full size="lg" disabled={busy} className="!mt-6">
          {busy ? "Creating account…" : "Create account"}
        </Button>
      </form>

      <div className="my-7 flex items-center gap-4">
        <span className="h-px flex-1 bg-pw-stroke/50" />
        <span className="text-[11px] font-medium tracking-wider text-pw-muted">OR SIGN UP WITH</span>
        <span className="h-px flex-1 bg-pw-stroke/50" />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Button variant="outline" type="button" disabled title="Coming soon">
          <FcGoogle className="h-4 w-4" /> Google
        </Button>
        <Button variant="outline" type="button" disabled title="Coming soon">
           Apple
        </Button>
      </div>

      <p className="mt-8 text-center text-[15px] text-pw-body">
        Already have an account?{" "}
        <Link to="/login" className="font-medium text-pw-button hover:underline">Log in</Link>
      </p>
    </AuthLayout>
  );
}
