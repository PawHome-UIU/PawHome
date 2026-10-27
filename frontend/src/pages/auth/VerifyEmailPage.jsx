import { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { FiCheckCircle, FiAlertCircle, FiMail } from "react-icons/fi";
import AuthLayout from "../../components/layout/AuthLayout";
import Logo from "../../components/brand/Logo";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * Where the confirmation link in the email lands.
 *
 * The link is a GET to this page; the page then POSTs the token. That extra
 * hop exists because corporate mail scanners fetch every URL in a message
 * before the recipient sees it -- a GET endpoint would be redeemed by the
 * scanner, and the real click would get "already used".
 *
 * The POST fires exactly once. React 18's StrictMode deliberately runs effects
 * twice in development, which would spend the token on the first call and show
 * the second one's failure; the ref guards against that.
 */
export default function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get("token");
  const [state, setState] = useState(token ? "working" : "missing");
  const [error, setError] = useState("");
  const [resendTo, setResendTo] = useState("");
  const [resent, setResent] = useState(false);
  const fired = useRef(false);

  async function resend(e) {
    e.preventDefault();
    try {
      await api.post("/auth/resend-verification", { email: resendTo.trim() });
    } catch {
      // The server answers the same whether or not the address is registered,
      // so there is nothing to branch on -- and a network error must not turn
      // into "no such account" on screen.
    }
    setResent(true);
  }

  useEffect(() => {
    if (!token || fired.current) return;
    fired.current = true;

    api
      .post(`/auth/verify-email?token=${encodeURIComponent(token)}`)
      .then(() => setState("done"))
      .catch((err) => {
        setError(apiError(err, "That link could not be used."));
        setState("failed");
      });
  }, [token]);

  const icon = {
    working: <FiMail className="h-8 w-8" />,
    done: <FiCheckCircle className="h-8 w-8" />,
    failed: <FiAlertCircle className="h-8 w-8" />,
    missing: <FiAlertCircle className="h-8 w-8" />,
  }[state];

  const heading = {
    working: "Confirming your email…",
    done: "Email confirmed",
    failed: "That link didn't work",
    missing: "That link is incomplete",
  }[state];

  return (
    <AuthLayout side="left" title="Almost there"
      tagline="A sanctuary for every pet, a home for every heart.">
      <div className="text-center">
        <Logo />

        <span className={[
          "mx-auto mt-7 flex h-16 w-16 items-center justify-center rounded-full",
          state === "done" ? "bg-green-50 text-green-700"
            : state === "working" ? "bg-pw-input text-pw-button"
            : "bg-rose-50 text-pw-danger",
        ].join(" ")}>
          {icon}
        </span>

        <h1 className="mt-6 text-2xl font-semibold">{heading}</h1>

        {state === "working" && (
          <p className="mt-3 text-[15px] text-pw-body">One moment.</p>
        )}

        {state === "done" && (
          <>
            <p className="mt-3 text-[15px] leading-relaxed text-pw-body">
              Your address is confirmed. If you signed up as shelter staff, an
              administrator still needs to approve the account before you can
              sign in.
            </p>
            <Button to="/login" full size="lg" className="mt-8">Sign in</Button>
          </>
        )}

        {(state === "failed" || state === "missing") && (
          <>
            <p className="mt-3 text-[15px] leading-relaxed text-pw-body">
              {state === "missing"
                ? "The link is missing its token. Copy the whole address from the email."
                : error}
            </p>
            {/* A link can expire honestly, so the way forward is a new one --
                not a dead end. Inline rather than another route: this is two
                fields, and a page for it would be a page nobody reaches
                except from here. */}
            {resent ? (
              <p className="mt-8 rounded-2xl bg-pw-input px-5 py-4 text-[15px] leading-relaxed text-pw-body">
                If that address needs confirming, a new link is on its way.
              </p>
            ) : (
              <form onSubmit={resend} className="mt-8 text-left">
                <Input
                  label="Your email"
                  type="email"
                  autoComplete="email"
                  placeholder="you@example.com"
                  value={resendTo}
                  onChange={(e) => setResendTo(e.target.value)}
                />
                <Button type="submit" full size="lg" className="mt-4"
                  disabled={!resendTo.trim()}>
                  Send me a new link
                </Button>
              </form>
            )}

            <Link to="/login"
              className="mt-5 inline-block text-sm font-medium text-pw-button hover:underline">
              Back to sign in
            </Link>
          </>
        )}
      </div>
    </AuthLayout>
  );
}
