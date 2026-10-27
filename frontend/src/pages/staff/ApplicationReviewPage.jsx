import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  FiArrowLeft, FiCheckCircle, FiAlertCircle, FiMapPin, FiImage,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import StatusBadge from "../../components/pets/StatusBadge";
import SuccessModal from "../../components/ui/SuccessModal";
import DeclineApplicationModal from "../../components/staff/DeclineApplicationModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

function Field({ label, value }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold uppercase tracking-wider text-pw-muted">{label}</dt>
      <dd className="mt-1 text-[15px] text-pw-ink">{value || <span className="text-pw-muted">—</span>}</dd>
    </div>
  );
}

const BADGE = "inline-flex h-6 items-center justify-center whitespace-nowrap rounded-pill px-2.5 "
            + "text-[11px] font-semibold leading-none";

const STATUS_TONE = {
  PENDING: "bg-amber-50 text-amber-700",
  APPROVED: "bg-green-50 text-green-700",
  REJECTED: "bg-rose-50 text-pw-danger",
  WITHDRAWN: "bg-pw-input text-pw-muted",
};

const REASON_LABEL = {
  INCOMPLETE_INFO: "Incomplete information",
  HOUSING_UNSUITABLE: "Housing unsuitable",
  INSUFFICIENT_EXPERIENCE: "Insufficient experience",
  PET_ALREADY_MATCHED: "Pet already matched",
  FAILED_ELIGIBILITY: "Failed eligibility",
  OTHER: "Other",
};

const when = (iso) => iso && new Date(iso).toLocaleString(undefined,
  { year: "numeric", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });

/**
 * One application, and the decision.
 *
 * APPROVING IS NOT A STATUS CHANGE. The server writes the adoption record,
 * gives the pet its new owner and closes every other open application for that
 * animal, all in one transaction -- so the confirmation here says so plainly
 * rather than "saved". Somebody clicking Approve is placing an animal, and the
 * screen should read like it.
 */
export default function ApplicationReviewPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const [app, setApp] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [declineOpen, setDeclineOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [notice, setNotice] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/staff/applications/${id}`);
        if (!cancelled) setApp(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load that application."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  async function decide(path, body, title, message) {
    setBusy(true);
    setActionError("");
    try {
      const { data } = await api.post(`/staff/applications/${id}/${path}`, body ?? {});
      setApp(data);
      setDeclineOpen(false);
      setNotice({ title, message });
    } catch (err) {
      // Approve has no modal of its own, so its refusal is shown on the page.
      const text = apiError(err, "That decision could not be recorded.");
      if (path === "decline") setActionError(text);
      else setNotice({ title: "That didn't work", message: text });
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <StaffLayout title="Application Review">
        <Card className="px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </Card>
      </StaffLayout>
    );
  }

  if (loadError || !app) {
    return (
      <StaffLayout title="Application Review">
        <Card className="px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">
            {loadError || "That application could not be found."}
          </p>
          <Button size="sm" className="mt-5" onClick={() => navigate("/staff/applications")}>
            Back to the queue
          </Button>
        </Card>
      </StaffLayout>
    );
  }

  const pending = app.status === "PENDING";

  return (
    <StaffLayout
      title="Application Review"
      subtitle={`${app.applicantName} · ${app.petName}`}
      actions={
        <Button variant="outline" size="sm" onClick={() => navigate("/staff/applications")}>
          <FiArrowLeft className="h-4 w-4" /> Go Back
        </Button>
      }
    >
      {!pending && (
        <div role="status"
          className={`mb-6 rounded-card border px-5 py-4 text-[15px] ${
            app.status === "APPROVED"
              ? "border-green-200 bg-green-50 text-green-800"
              : "border-pw-stroke/50 bg-pw-input text-pw-body"}`}>
          {app.status === "APPROVED"
            ? `Approved${app.reviewedByName ? ` by ${app.reviewedByName}` : ""} on ${when(app.reviewedAt)}. `
              + `${app.petName} has been placed with ${app.applicantName}, and the other open applications for this pet were closed.`
            : app.status === "WITHDRAWN"
              // Guarded: rows withdrawn before withdraw() recorded a time have
              // no date to show, and "on undefined" is worse than no date.
              ? `The applicant withdrew this${app.reviewedAt ? ` on ${when(app.reviewedAt)}` : ""}, `
                + "before a decision was made."
              : `Declined${app.reviewedByName ? ` by ${app.reviewedByName}` : ""} on ${when(app.reviewedAt)}.`}
        </div>
      )}

      {actionError && (
        <p role="alert" className="mb-6 rounded-card bg-rose-50 px-5 py-4 text-[15px] text-pw-danger">
          {actionError}
        </p>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card className="p-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="text-base font-semibold">Applicant</h2>
              <span className={`${BADGE} ${STATUS_TONE[app.status]}`}>{app.status}</span>
            </div>
            {/* The application's OWN copy of these details, not the user's
                current ones. An application is a record of what somebody said
                at the time, and it must not change when they move house. */}
            <dl className="mt-5 grid gap-5 sm:grid-cols-2">
              <Field label="Name" value={app.applicantName} />
              <Field label="Email" value={app.applicantEmail} />
              <Field label="Phone" value={app.applicantPhone} />
              <Field label="City" value={app.applicantCity} />
              <div className="sm:col-span-2">
                <Field label="Address" value={app.applicantAddress} />
              </div>
              <Field label="Home" value={app.homeType} />
              <Field label="Time at home" value={app.timeAtHome} />
              <div className="sm:col-span-2">
                <Field label="Experience with pets" value={app.petExperience} />
              </div>
              {app.householdNote && (
                <div className="sm:col-span-2">
                  <Field label="Household note" value={app.householdNote} />
                </div>
              )}
            </dl>
          </Card>

          {app.status === "REJECTED" && (
            <Card className="p-6">
              <h2 className="text-base font-semibold">Why it was declined</h2>
              <p className="mt-2 text-sm text-pw-muted">
                {REASON_LABEL[app.declineReasonCode] ?? app.declineReasonCode}
              </p>
              <p className="mt-3 text-[15px] leading-relaxed text-pw-body">{app.declineMessage}</p>
            </Card>
          )}
        </div>

        <div className="space-y-6">
          <Card className="overflow-hidden">
            <div className="relative aspect-[4/3] bg-pw-sidebar/25 overflow-hidden shrink-0">
              {app.petCoverPhotoPath ? (
                <img src={`${API_BASE}/files/${app.petCoverPhotoPath}`} alt={app.petName}
                  className="absolute inset-0 h-full w-full object-cover" />
              ) : (
                <span className="flex h-full w-full items-center justify-center text-pw-stroke">
                  <FiImage className="h-8 w-8" />
                </span>
              )}
              <span className="absolute left-3 top-3"><StatusBadge status={app.petStatus} /></span>
            </div>
            <div className="p-5">
              <h3 className="text-lg font-semibold leading-tight">{app.petName}</h3>
              <p className="mt-1 text-sm text-pw-muted">{app.petBreed}</p>
              <p className="mt-3 flex items-center gap-1.5 text-sm text-pw-body">
                <FiMapPin className="h-4 w-4 text-pw-muted" /> {app.shelterName}
              </p>
            </div>
          </Card>

          <Card className="p-6">
            <h2 className="text-base font-semibold">Eligibility quiz</h2>
            {/* As a fraction, and with the server's own verdict. The maximum
                moves when the bank changes, so a bare number would mean
                something different from one week to the next. */}
            <p className="mt-3 text-3xl font-semibold tabular-nums text-pw-ink">
              {app.quizScore}
              {app.quizMaxScore != null && (
                <span className="text-lg text-pw-muted"> / {app.quizMaxScore}</span>
              )}
            </p>
            <p className={`mt-3 inline-flex items-center gap-1.5 ${BADGE} ${
              app.quizPassed === true ? "bg-green-50 text-green-700"
                : app.quizPassed === false ? "bg-rose-50 text-pw-danger"
                : "bg-pw-input text-pw-body"}`}>
              {app.quizPassed === true ? <FiCheckCircle className="h-3.5 w-3.5" />
                : <FiAlertCircle className="h-3.5 w-3.5" />}
              {app.quizPassed === true ? "Met the bar"
                : app.quizPassed === false ? "Below the bar" : "Not scored"}
            </p>
            {app.quizPassed === false && (
              // Failing is not an automatic refusal -- the schema has a
              // FAILED_ELIGIBILITY decline code, which only makes sense if a
              // person still decides.
              <p className="mt-3 text-sm leading-relaxed text-pw-muted">
                Below the bar is a flag, not a decision. You can still approve
                this if you know something the quiz does not.
              </p>
            )}
            <p className="mt-4 text-xs text-pw-muted">Submitted {when(app.submittedAt)}</p>
          </Card>

          {pending && (
            <Card className="p-6">
              <h2 className="text-base font-semibold">Decision</h2>
              <p className="mt-2 text-sm leading-relaxed text-pw-muted">
                Approving places {app.petName} with {app.applicantName}, records
                the adoption, and closes the other open applications for this pet.
              </p>
              <Button full className="mt-5" disabled={busy}
                onClick={() => decide("approve", null, `${app.petName} placed`,
                  `${app.applicantName} is now ${app.petName}'s owner, and the other open applications for this pet have been closed.`)}>
                <FiCheckCircle className="h-4 w-4" />
                {busy ? "Working…" : "Approve application"}
              </Button>
              <Button full variant="outline" className="mt-3 !border-pw-danger !text-pw-danger"
                disabled={busy} onClick={() => { setActionError(""); setDeclineOpen(true); }}>
                Decline application
              </Button>
            </Card>
          )}
        </div>
      </div>

      {declineOpen && (
        <DeclineApplicationModal
          open
          application={app}
          busy={busy}
          error={actionError}
          onClose={() => setDeclineOpen(false)}
          onDecline={(body) => decide("decline", body, "Application declined",
            "The applicant can read your message on their applications page.")}
        />
      )}

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? ""}
      />
    </StaffLayout>
  );
}
