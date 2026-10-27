import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  FiMapPin, FiCheck, FiClock, FiX, FiImage, FiRefreshCw, FiCornerUpLeft,
} from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Tabs from "../../components/ui/Tabs";
import SuccessModal from "../../components/ui/SuccessModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * What I have applied for, and where each one got to.
 *
 * THE DECLINE MESSAGE IS SHOWN IN FULL, and that is the point of the screen.
 * ck_apps_decline forces a shelter to write both a reason code and a sentence
 * before an application can be rejected; hiding that sentence behind "Declined"
 * would make the constraint pointless and leave the person with no idea what
 * to change before applying for the next animal.
 */
const BADGE = "inline-flex h-6 items-center justify-center whitespace-nowrap rounded-pill px-2.5 "
            + "text-[11px] font-semibold leading-none";

const STATUS = {
  PENDING: { tone: "bg-amber-50 text-amber-700", icon: FiClock, label: "Waiting for a decision" },
  APPROVED: { tone: "bg-green-50 text-green-700", icon: FiCheck, label: "Approved" },
  REJECTED: { tone: "bg-rose-50 text-pw-danger", icon: FiX, label: "Declined" },
  WITHDRAWN: { tone: "bg-pw-input text-pw-muted", icon: FiCornerUpLeft, label: "Withdrawn" },
};

const when = (iso) => iso && new Date(iso).toLocaleDateString(undefined,
  { year: "numeric", month: "short", day: "numeric" });

// adoptedOn is a LocalDate -- "2026-09-04", no time and no zone. Passing that
// to new Date() parses it as UTC MIDNIGHT, which anywhere behind UTC renders
// as the day before, so the adoption would appear to have happened a day early
// for every adopter in the Americas. Split it and build a local date instead.
const day = (iso) => {
  if (!iso) return null;
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

export default function MyApplicationsPage() {
  const [filter, setFilter] = useState("ALL");
  const [apps, setApps] = useState([]);
  const [selected, setSelected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [busyId, setBusyId] = useState(null);
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/applications/mine");
      setApps(data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your applications."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/applications/mine");
        if (cancelled) return;
        setApps(data);
        setSelected(data[0]?.id ?? null);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your applications."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  async function withdraw(id) {
    setBusyId(id);
    try {
      await api.post(`/applications/${id}/withdraw`);
      await load();
      setNotice({
        title: "Application withdrawn",
        message: "The shelter can see it was withdrawn rather than simply gone.",
      });
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  const rows = useMemo(
    () => (filter === "ALL" ? apps : apps.filter((a) => a.status === filter)),
    [apps, filter]);

  // Derived and clamped, never corrected by an effect: the selected row can
  // disappear under a filter change or a reload.
  const active = rows.find((a) => a.id === selected) ?? rows[0] ?? null;

  const count = (s) => apps.filter((a) => a.status === s).length;

  return (
    <AdopterLayout>
      <nav aria-label="Breadcrumb" className="mb-2 text-sm text-pw-muted">
        <Link to="/app" className="hover:text-pw-button">Dashboard</Link>
        <span className="mx-2">›</span>
        <span className="font-medium text-pw-button">Applications</span>
      </nav>

      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-3xl font-semibold tracking-tight">My Applications</h1>
        <div className="flex flex-wrap items-center gap-3">
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          <Tabs
            value={filter} onChange={setFilter} className="!border-0"
            tabs={[
              { key: "ALL", label: "All", count: apps.length },
              { key: "PENDING", label: "Pending", count: count("PENDING") },
              { key: "APPROVED", label: "Approved", count: count("APPROVED") },
              { key: "REJECTED", label: "Declined", count: count("REJECTED") },
              { key: "WITHDRAWN", label: "Withdrawn", count: count("WITHDRAWN") },
            ]}
          />
        </div>
      </div>

      {loadError ? (
        <Card className="mt-8 px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      ) : loading ? (
        <Card className="mt-8 px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </Card>
      ) : apps.length === 0 ? (
        <Card className="mt-8 px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-ink">You haven't applied for anyone yet.</p>
          <p className="mt-1.5 text-sm text-pw-muted">
            Find a pet you like and the apply button is on their profile.
          </p>
          <Button to="/app/pets" className="mt-6">Browse pets</Button>
        </Card>
      ) : (
        <div className="mt-8 grid gap-6 lg:grid-cols-5">
          <div className="space-y-4 lg:col-span-3">
            {rows.length === 0 && (
              <Card className="px-6 py-16 text-center">
                <p className="text-[15px] font-medium text-pw-ink">Nothing with that status.</p>
              </Card>
            )}

            {rows.map((a) => {
              const s = STATUS[a.status];
              const Icon = s.icon;
              return (
                <Card key={a.id} selected={active?.id === a.id}
                  className="cursor-pointer p-5 transition-shadow hover:shadow-card"
                  onClick={() => setSelected(a.id)}>
                  <div className="flex items-start gap-4">
                    <span className="flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-card bg-pw-sidebar/25">
                      {a.petCoverPhotoPath
                        ? <img src={`${API_BASE}/files/${a.petCoverPhotoPath}`} alt=""
                            className="h-full w-full object-cover" />
                        : <FiImage className="h-5 w-5 text-pw-stroke" />}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <h2 className="text-lg font-semibold leading-tight">{a.petName}</h2>
                        <span className={`${BADGE} ${s.tone}`}>
                          <Icon className="mr-1 h-3 w-3" /> {a.status}
                        </span>
                      </div>
                      <p className="mt-1 text-sm text-pw-muted">{a.petBreed}</p>
                      <p className="mt-2 flex items-center gap-1.5 text-sm text-pw-body">
                        <FiMapPin className="h-4 w-4 text-pw-muted" /> {a.shelterName}
                      </p>
                      <p className="mt-2 text-xs text-pw-muted">Applied {when(a.submittedAt)}</p>
                    </div>
                  </div>
                </Card>
              );
            })}
          </div>

          {active && (
            <div className="lg:col-span-2">
              <Card className="p-6">
                <h2 className="text-base font-semibold">{active.petName}</h2>
                <p className="mt-1 text-sm text-pw-muted">{STATUS[active.status].label}</p>

                {active.status === "PENDING" && (
                  <>
                    <p className="mt-5 text-[15px] leading-relaxed text-pw-body">
                      {active.shelterName} has your application. They typically
                      answer within a few days.
                    </p>
                    <Button variant="outline" full className="mt-5 !border-pw-danger !text-pw-danger"
                      disabled={busyId === active.id} onClick={() => withdraw(active.id)}>
                      {busyId === active.id ? "Withdrawing…" : "Withdraw this application"}
                    </Button>
                  </>
                )}

                {active.status === "APPROVED" && (
                  <p className="mt-5 rounded-card bg-green-50 px-4 py-3 text-[15px] leading-relaxed text-green-800">
                    {active.petName} is yours. {active.shelterName} will be in
                    touch about collecting them.
                  </p>
                )}

                {active.status === "REJECTED" && (
                  // The sentence the shelter had to write. See the note above.
                  <div className="mt-5 rounded-card bg-rose-50 px-4 py-3">
                    <p className="text-sm font-semibold text-pw-danger">
                      What {active.shelterName} said
                    </p>
                    <p className="mt-2 text-[15px] leading-relaxed text-pw-body">
                      {active.declineMessage}
                    </p>
                  </div>
                )}

                {active.status === "WITHDRAWN" && (
                  <p className="mt-5 text-[15px] leading-relaxed text-pw-body">
                    You took this one back on {when(active.reviewedAt) ?? "an earlier date"}.
                  </p>
                )}

                <dl className="mt-6 space-y-3 border-t border-pw-stroke/40 pt-5 text-sm">
                  <div className="flex justify-between gap-4">
                    <dt className="text-pw-muted">Applied</dt>
                    <dd className="text-pw-ink">{when(active.submittedAt)}</dd>
                  </div>
                  {active.reviewedAt && (
                    <div className="flex justify-between gap-4">
                      {/* The same column means two different things: a shelter
                          answered it, or the applicant closed it themselves. */}
                      <dt className="text-pw-muted">
                        {active.status === "WITHDRAWN" ? "Withdrawn" : "Answered"}
                      </dt>
                      <dd className="text-pw-ink">{when(active.reviewedAt)}</dd>
                    </div>
                  )}
                  {/* The adoptions row approve() wrote in the same transaction.
                      Guarded rather than keyed off APPROVED: the database does
                      not enforce that an approved application has one, and a
                      missing date should show nothing, not "Invalid Date". */}
                  {active.adoptedOn && (
                    <>
                      <div className="flex justify-between gap-4">
                        <dt className="text-pw-muted">Adopted on</dt>
                        <dd className="text-pw-ink">{day(active.adoptedOn)}</dd>
                      </div>
                      <div className="flex justify-between gap-4">
                        <dt className="text-pw-muted">Fee paid</dt>
                        <dd className="text-pw-ink tabular-nums">
                          ৳{Number(active.feePaid ?? 0).toFixed(2)}
                        </dd>
                      </div>
                    </>
                  )}
                  <div className="flex justify-between gap-4">
                    <dt className="text-pw-muted">Eligibility quiz</dt>
                    <dd className="text-pw-ink tabular-nums">
                      {active.quizScore}{active.quizMaxScore != null && ` / ${active.quizMaxScore}`}
                    </dd>
                  </div>
                </dl>

                <Button to={`/app/pets/${active.petId}`} variant="outline" full className="mt-6">
                  View {active.petName}'s profile
                </Button>
              </Card>
            </div>
          )}
        </div>
      )}

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? ""}
      />
    </AdopterLayout>
  );
}
