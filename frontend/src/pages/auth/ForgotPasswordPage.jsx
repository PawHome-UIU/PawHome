import { useState } from "react";
import { Link } from "react-router-dom";
import { FiArrowLeft, FiMail } from "react-icons/fi";
import AuthLayout from "../../components/layout/AuthLayout";
import Logo from "../../components/brand/Logo";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import api from "../../api/client";

/**
 * Forgot password.
 *
 * The login page has linked here since the day it was written; the route did
 * not exist, so the link fell through to the catch-all and dumped people on the
 * landing page — the single worst place to send someone who cannot get in.
 *
 * THE RESPONSE IS THE SAME WHETHER THE ACCOUNT EXISTS OR NOT. "No account with
 * that email" turns this form into a way to find out who has an account here,
 * which for an adoption service is a real disclosure: it says who in a town is
 * looking to adopt. The confirmation below says what was done, not what was
 * found, and the server sends mail only if there is somewhere to send it.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    if (!email.trim()) return setError("Enter the email you signed up with.");
    if (!/^\S+@\S+\.\S+$/.test(email)) return setError("That doesn't look like an email address.");
    setError("");
    setBusy(true);
    try {
      // The server replies identically whether or not the account exists, so
      // there is nothing in the response worth branching on -- and a failure
      // here must not become "no such account" on screen.
      await api.post("/auth/forgot-password", { email: email.trim() });
    } catch {
      // Even a network error shows the same confirmation. Saying "that address
      // is not registered" is exactly what this flow avoids.
    } finally {
      setBusy(false);
      setSent(true);
    }
  }

  if (sent) {
    return (
      <AuthLayout
        side="left"
        title="Check your email"
        tagline="A sanctuary for every pet, a home for every heart."
      >
        <div className="text-center">
          <Logo />

          <span className="mx-auto mt-7 flex h-16 w-16 items-center justify-center rounded-full bg-pw-input text-pw-button">
            <FiMail className="h-7 w-7" />
          </span>

          <h1 className="mt-6 text-2xl font-semibold">Check your email</h1>

          <p className="mt-6 text-[15px] leading-relaxed text-pw-body">
            We sent a link to <span className="font-medium text-pw-ink">{email}</span>.
            It works once and expires in an hour.
          </p>
          <p className="mt-3 text-sm text-pw-muted">
            Nothing after a few minutes? Check spam, and make sure that is the
            address you signed up with.
          </p>

          <Button to="/login" full size="lg" className="mt-8">Back to sign in</Button>

          <button
            type="button"
            onClick={() => setSent(false)}
            className="mt-4 text-sm font-medium text-pw-button hover:underline"
          >
            Use a different email
          </button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      side="left"
      title="Reset your password"
      tagline="It happens. Let's get you back to your pets."
    >
      <form onSubmit={submit} noValidate>
        <div className="text-center">
          <Logo />
          <h1 className="mt-7 text-2xl font-semibold">Reset your password</h1>
          <p className="mt-2 text-[15px] text-pw-body">
            Enter your email and we'll send a link to set a new one.
          </p>
        </div>

        <div className="mt-8" />
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="you@example.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={error}
        />

        <Button type="submit" full size="lg" className="mt-6" disabled={busy}>
          {busy ? "Sending…" : "Send reset link"}
        </Button>

        <Link
          to="/login"
          className="mt-6 flex items-center justify-center gap-1.5 text-sm font-medium text-pw-button hover:underline"
        >
          <FiArrowLeft className="h-4 w-4" /> Back to sign in
        </Link>
      </form>
    </AuthLayout>
  );
}
