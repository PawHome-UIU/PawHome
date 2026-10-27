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
 * Where the reset link lands.
 *
 * SETTING A NEW PASSWORD DOES NOT SIGN YOU IN. The link may have been read by
 * someone who reached the inbox rather than the person who owns it, so the
 * flow ends at a login form: an attacker gets one password change, which is
 * loud and noticed, rather than a silent session.
 */
export default function ResetPasswordPage() {
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
      await api.post("/auth/reset-password", { token, password: form.password });
      setDone(true);
    } catch (err) {
      setFormError(apiError(err, "That reset link could not be used."));
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <AuthLayout side="left" title="Reset your password" tagline="Let's get you back in.">
        <div className="text-center">
          <Logo />
          <h1 className="mt-7 text-2xl font-semibold">That link is incomplete</h1>
          <p className="mt-3 text-[15px] text-pw-body">
            It is missing its token. Copy the whole address from the email.
          </p>
          <Button to="/forgot-password" full size="lg" className="mt-8">
            Ask for a new link
          </Button>
        </div>
      </AuthLayout>
    );
  }

  if (done) {
    return (
      <AuthLayout side="left" title="All set" tagline="Let's get you back in.">
        <div className="text-center">
          <Logo />
          <span className="mx-auto mt-7 flex h-16 w-16 items-center justify-center rounded-full bg-green-50 text-green-700">
            <FiCheckCircle className="h-8 w-8" />
          </span>
          <h1 className="mt-6 text-2xl font-semibold">Password changed</h1>
          <p className="mt-3 text-[15px] leading-relaxed text-pw-body">
            You can sign in with your new password now.
          </p>
          <Button to="/login" full size="lg" className="mt-8">Sign in</Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout side="left" title="Choose a new password" tagline="Let's get you back in.">
      <form onSubmit={onSubmit} noValidate>
        <div className="text-center">
          <Logo />
          <h1 className="mt-7 text-2xl font-semibold">Choose a new password</h1>
          <p className="mt-2 text-[15px] text-pw-body">
            Four unrelated words beat eight characters of punctuation.
          </p>
        </div>

        {formError && (
          <p role="alert" className="mt-6 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {formError}
          </p>
        )}

        <div className="mt-6 space-y-4">
          <div>
            <Input label="New password" type="password" autoComplete="new-password"
              placeholder="••••••••" value={form.password}
              onChange={set("password")} error={errors.password} />
            <PasswordChecklist password={form.password} />
          </div>
          <Input label="Confirm new password" type="password" autoComplete="new-password"
            placeholder="••••••••" value={form.confirm}
            onChange={set("confirm")} error={errors.confirm} />
        </div>

        <Button type="submit" full size="lg" className="mt-7" disabled={busy}>
          {busy ? "Saving…" : "Change password"}
        </Button>

        <Link to="/login"
          className="mt-6 block text-center text-sm font-medium text-pw-button hover:underline">
          Back to sign in
        </Link>
      </form>
    </AuthLayout>
  );
}
