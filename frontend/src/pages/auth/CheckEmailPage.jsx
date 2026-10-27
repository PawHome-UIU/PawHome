import { useState } from "react";
import { Link, Navigate, useLocation } from "react-router-dom";
import { FiMail, FiExternalLink, FiClock } from "react-icons/fi";
import AuthLayout from "../../components/layout/AuthLayout";
import Logo from "../../components/brand/Logo";
import Button from "../../components/ui/Button";
import api from "../../api/client";
import { mailProviderFor } from "../../lib/mailProvider";

/**
 * Where registration lands.
 *
 * Registration used to send people straight to /login with "You can log in
 * now", which stopped being true the moment email verification was added --
 * they would have typed a correct password and been refused. This page says
 * the one thing they need to do next, and gives them a button to go and do it.
 *
 * The email arrives via router state rather than a query string: it is only
 * used to name the address and pick the provider, and putting it in the URL
 * would leave it in history and in any referrer header.
 */
export default function CheckEmailPage() {
  const { state } = useLocation();
  const email = state?.email;
  const isStaff = state?.role === "STAFF";

  const [resent, setResent] = useState(false);
  const [busy, setBusy] = useState(false);

  // Reached directly, with no registration behind it. Nothing sensible to show.
  if (!email) return <Navigate to="/register" replace />;

  const provider = mailProviderFor(email);

  async function resend() {
    setBusy(true);
    try {
      await api.post("/auth/resend-verification", { email });
    } catch {
      // The server answers identically whether or not the address needs
      // confirming, so there is nothing here worth showing differently.
    } finally {
      setBusy(false);
      setResent(true);
    }
  }

  return (
    <AuthLayout
      side="left"
      title="Check your email"
      tagline="One click and you're in."
    >
      <div className="text-center">
        <Logo />

        <span className="mx-auto mt-7 flex h-16 w-16 items-center justify-center rounded-full bg-pw-input text-pw-button">
          <FiMail className="h-8 w-8" />
        </span>

        <h1 className="mt-6 text-2xl font-semibold">Confirm your email</h1>

        <p className="mt-3 text-[15px] leading-relaxed text-pw-body">
          We've sent a link to{" "}
          <span className="font-medium text-pw-ink">{email}</span>. Click it and
          your account is ready.
        </p>

        {/* Only when the domain is one we recognise. A button that opens the
            wrong provider is worse than no button. */}
        {provider && (
          <Button
            href={provider.url}
            target="_blank"
            rel="noopener noreferrer"
            full
            size="lg"
            className="mt-7"
          >
            Open {provider.name} <FiExternalLink className="h-4 w-4" />
          </Button>
        )}

        {isStaff && (
          <div className="mt-5 flex gap-3 rounded-card bg-pw-input p-4 text-left">
            <FiClock className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
            <p className="text-sm leading-relaxed text-pw-body">
              You signed up as shelter staff, so there is one more step: after
              you confirm your address, an administrator approves the account.
              You'll be able to sign in once they have.
            </p>
          </div>
        )}

        <div className="mt-7 border-t border-pw-stroke/40 pt-5">
          {resent ? (
            <p className="text-sm text-pw-muted">
              Sent again. It can take a minute to arrive — check spam too.
            </p>
          ) : (
            <p className="text-sm text-pw-muted">
              Nothing yet?{" "}
              <button
                type="button"
                onClick={resend}
                disabled={busy}
                className="font-medium text-pw-button hover:underline disabled:opacity-60"
              >
                {busy ? "Sending…" : "Send it again"}
              </button>
            </p>
          )}

          <Link
            to="/login"
            className="mt-4 inline-block text-sm font-medium text-pw-button hover:underline"
          >
            Back to sign in
          </Link>
        </div>
      </div>
    </AuthLayout>
  );
}
