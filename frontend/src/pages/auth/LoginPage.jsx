import { useState } from "react";
import { Link, useNavigate, useLocation } from "react-router-dom";
import { FcGoogle } from "react-icons/fc";
import { FiArrowRight } from "react-icons/fi";
import AuthLayout from "../../components/layout/AuthLayout";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import Checkbox from "../../components/ui/Checkbox";
import Logo from "../../components/brand/Logo";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { useAuth, HOME_FOR } from "../../context/AuthContext";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { login } = useAuth();
  const [form, setForm] = useState({ email: "", password: "" });
  const [errors, setErrors] = useState({});
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);

  const set = (k) => (e) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    if (errors[k]) setErrors((x) => ({ ...x, [k]: undefined }));
  };

  function validate() {
    const next = {};
    if (!form.email) next.email = "Email address is required";
    else if (!EMAIL_RE.test(form.email)) next.email = "Invalid email address";
    if (!form.password) next.password = "Password is required";
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  async function onSubmit(e) {
    e.preventDefault();
    setFormError("");
    if (!validate()) return;

    setBusy(true);
    try {
      const { data } = await api.post("/auth/login", form);
      // Through the context, not straight into localStorage: ProtectedRoute
      // reads the context, and writing only to storage would leave it showing
      // the spinner until a full page reload.
      login(data.token, data.user);
      // Back where they were headed if a guard sent them here, otherwise their
      // own portal -- each role has a different one.
      const from = location.state?.from;
      navigate(from || HOME_FOR[data.user?.role] || "/app", { replace: true });
    } catch (err) {
      // One message for a bad email AND a bad password, deliberately: telling
      // them which half was wrong turns this form into a way to discover which
      // email addresses have accounts.
      setFormError(apiError(err, "Email or password is incorrect."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      side="left"
      title="Welcome back to PawHome"
      tagline="A sanctuary for every pet, a home for every heart."
    >
      <div className="text-center">
        <Logo />
        <h1 className="mt-7 text-2xl font-semibold">Log in to your account</h1>
        <p className="mt-1.5 text-[15px] text-pw-muted">
          Connecting pets with loving families
        </p>
      </div>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-4">
        {formError && (
          <p role="alert"
            className="rounded-2xl border border-pw-danger/30 bg-pw-danger/5 px-4 py-3 text-sm text-pw-danger">
            {formError}
          </p>
        )}

        <Input
          label="Email Address" type="email" autoComplete="email" placeholder="you@example.com"
          value={form.email} onChange={set("email")} error={errors.email}
        />
        <Input
          label="Password" type="password" autoComplete="current-password" placeholder="••••••••"
          value={form.password} onChange={set("password")} error={errors.password}
        />

        <div className="flex items-center justify-between pt-1">
          <Checkbox label="Remember me" />
          <Link to="/forgot-password" className="text-sm font-medium text-pw-button hover:underline">
            Forgot password?
          </Link>
        </div>

        <Button type="submit" full size="lg" disabled={busy} className="!mt-6">
          {busy ? "Logging in…" : <>Log in <FiArrowRight className="h-4 w-4" /></>}
        </Button>
      </form>

      <div className="my-7 flex items-center gap-4">
        <span className="h-px flex-1 bg-pw-stroke/50" />
        <span className="text-[11px] font-medium tracking-wider text-pw-muted">OR CONTINUE WITH</span>
        <span className="h-px flex-1 bg-pw-stroke/50" />
      </div>

      {/* Present in the design; no OAuth provider is wired yet, so these say so
          rather than failing silently when clicked. */}
      <div className="grid grid-cols-2 gap-3">
        <Button variant="outline" type="button" disabled title="Coming soon">
          <FcGoogle className="h-4 w-4" /> Google
        </Button>
        <Button variant="outline" type="button" disabled title="Coming soon">
           Apple
        </Button>
      </div>

      <p className="mt-8 text-center text-[15px] text-pw-body">
        Don't have an account?{" "}
        <Link to="/join" className="font-medium text-pw-button hover:underline">Sign up</Link>
      </p>
    </AuthLayout>
  );
}
