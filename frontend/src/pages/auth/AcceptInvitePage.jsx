import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { FiCheckCircle } from "react-icons/fi";
import AuthLayout from "../../components/layout/AuthLayout";
import Logo from "../../components/brand/Logo";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { passwordError } from "../../lib/passwordPolicy";
import PasswordChecklist from "../../components/auth/PasswordChecklist";

/**
 * Where a staff invitation lands.
 *
 * Almost the same shape as ResetPasswordPage and deliberately NOT merged with
 * it. The two flows differ in the sentence that matters most: a reset says
 * "somebody asked to change this password", an invitation says "an
 * administrator has added you to a shelter". Sharing one component means one
 * of those messages is wrong half the time, and the wrong one reads exactly
 * like a phishing email.
 *
 * SETTING THE PASSWORD DOES NOT SIGN YOU IN, for the same reason as a reset:
 * the link may have been read by whoever reached the inbox rather than the
 * person it was meant for. Ending at a login form makes that one loud step
 * instead of a silent session.
 */
export default function AcceptInvitePage() {
  const [params] = useSearchParams();
  const token = params.get("token");

  const [form, setForm] = useState({ password: "", confirm: "" });
  const [errors, setErrors] = useState({});
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const set = (k) => (e) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    if (errors[k]) setErrors((x) => ({ ...x, [k]: undefined }));
  };

  function validate() {
    const n = {};
    // The same rules as registration, from the same module -- otherwise
    // someone sets a password here they could never have set at signup.
    const pw = passwordError(form.password);
    if (pw) n.password = pw;
    if (form.confirm !== form.password) n.confirm = "Passwords do not match";
    setErrors(n);
    return Object.keys(n).length === 0;
  }

  async function onSubmit(e) {
    e.preventDefault();
    setFormError("");
    if (!validate()) return;

    setBusy(true);
    try {
      await api.post("/auth/accept-invite", { token, password: form.password });
      setDone(true);
    } catch (err) {
      setFormError(apiError(err, "That invitation could not be used."));
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <AuthLayout side="left" title="Join your shelter" tagline="Welcome to PawHome.">
        <div className="text-center">
          <Logo />
          <h1 className="mt-7 text-2xl font-semibold">That link is incomplete</h1>
          <p className="mt-3 text-[15px] text-pw-body">
            It is missing its token. Copy the whole address from the email, or
            ask your administrator to send the invitation again.
          </p>
          <Button to="/login" full size="lg" className="mt-8">Back to sign in</Button>
        </div>
      </AuthLayout>
    );
  }

  if (done) {
    return (
      <AuthLayout side="left" title="You're in" tagline="Welcome to PawHome.">
        <div className="text-center">
          <Logo />
          <span className="mx-auto mt-7 flex h-16 w-16 items-center justify-center rounded-full bg-green-50 text-green-700">
            <FiCheckCircle className="h-8 w-8" />
          </span>
          <h1 className="mt-6 text-2xl font-semibold">Your account is ready</h1>
          <p className="mt-3 text-[15px] leading-relaxed text-pw-body">
            Sign in and you will land in your shelter's staff portal.
          </p>
          <Button to="/login" full size="lg" className="mt-8">Sign in</Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout side="left" title="Join your shelter" tagline="Welcome to PawHome.">
      <form onSubmit={onSubmit} noValidate>
        <div className="text-center">
          <Logo />
          <h1 className="mt-7 text-2xl font-semibold">Choose your password</h1>
          <p className="mt-2 text-[15px] text-pw-body">
            An administrator has already set up your account. This is the last step.
          </p>
        </div>

        {formError && (
          <p role="alert" className="mt-6 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {formError}
          </p>
        )}

        <div className="mt-6 space-y-4">
          <div>
            <Input label="Password" type="password" autoComplete="new-password"
              placeholder="••••••••" value={form.password}
              onChange={set("password")} error={errors.password} />
            <PasswordChecklist password={form.password} />
          </div>
          <Input label="Confirm password" type="password" autoComplete="new-password"
            placeholder="••••••••" value={form.confirm}
            onChange={set("confirm")} error={errors.confirm} />
        </div>

        <Button type="submit" full size="lg" className="mt-7" disabled={busy}>
          {busy ? "Setting up…" : "Set my password"}
        </Button>

        <Link to="/login"
          className="mt-6 block text-center text-sm font-medium text-pw-button hover:underline">
          Back to sign in
        </Link>
      </form>
    </AuthLayout>
  );
}
