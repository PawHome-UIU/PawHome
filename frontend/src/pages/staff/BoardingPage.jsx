import { useCallback, useEffect, useMemo, useState } from "react";
import {
  FiClock, FiHome, FiLogIn, FiLogOut, FiCamera, FiCheck, FiX, FiShield,
  FiCalendar, FiImage, FiRefreshCw,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import StatTile from "../../components/admin/StatTile";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Modal from "../../components/ui/Modal";
import Tabs from "../../components/ui/Tabs";
import SuccessModal from "../../components/ui/SuccessModal";
import PostBoardingUpdateModal from "../../components/staff/PostBoardingUpdateModal";
import DeclineBoardingModal from "../../components/staff/DeclineBoardingModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";
import SectionTabs from "../../components/portal/SectionTabs";
import { STAFF_BOARDING_TABS } from "../../lib/navTabs";
import CheckOutModal from "../../components/staff/CheckOutModal";
import StayManageModal from "../../components/staff/StayManageModal";
import Pagination from "../../components/ui/Pagination";
import { usePaged } from "../../lib/usePaged";

/**
 * The shelter's boarding board.
 *
 * FOUR TABS, BECAUSE A STAY HAS FOUR USEFUL PHASES and each has a different
 * action: a request is accepted or declined, an accepted stay is checked in, an
 * active one gets updates and is checked out, and a finished one is only read.
 * The mock had two, which left "accepted but not yet arrived" with nowhere to
 * live and no way to check anything in.
 *
 * ACCEPTING IS THE ONLY PLACE THE ROOM IS DECIDED, and the dialog below says
 * out loud what the server does: the overlap check runs inside a transaction
 * holding row locks, so two staff members accepting the same animal in the
 * same second cannot both win. That note is not decoration -- it is there so
 * nobody "optimises" the lock away.
 */
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { month: "short", day: "numeric" });
};

/** Whole days from a LocalDate string to today, never negative. */
function daysSince(iso) {
  if (!iso) return 0;
  const [y, m, d] = iso.split("-").map(Number);
  const start = new Date(y, m - 1, d);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.max(0, Math.round((today - start) / 86400000));
}

/**
 * The accept dialog.
 *
 * It fetches the suites for THIS request rather than being handed a list: the
 * server filters them by the request's own species and dates, so a dog run
 * never appears for a cat and a room already booked that week arrives marked
 * as taken. A dropdown filled client-side could do neither.
 */
function AcceptModal({ open, onClose, request, onAccept, busy }) {
  const [suites, setSuites] = useState([]);
  const [suiteId, setSuiteId] = useState("");
  const [loadError, setLoadError] = useState("");

  useEffect(() => {
    if (!open || !request) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/staff/boarding/${request.id}/suites`);
        if (!cancelled) { setSuites(data); setLoadError(""); }
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the suites."));
      }
    })();
    return () => { cancelled = true; };
  }, [open, request]);

  // `occupied` means FULL since V16 -- a room with one dog in it and three
  // places left is still offerable, and greying it out would tell the shelter
  // it has nowhere to put an animal it has room for.
  const free = suites.filter((s) => !s.occupied);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Accept Boarding Request"
      subtitle={request ? `${request.petName} · ${day(request.startDate)} – ${day(request.endDate)}` : ""}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button size="sm" disabled={busy}
            onClick={() => onAccept?.(suiteId ? Number(suiteId) : null)}>
            {busy ? "Accepting…" : "Accept & Assign"}
          </Button>
        </>
      }>
      <div className="space-y-4">
        <div>
          <label htmlFor="suite" className="mb-1.5 block text-sm font-medium text-pw-ink">
            Assign a suite
          </label>
          <select id="suite" value={suiteId} onChange={(e) => setSuiteId(e.target.value)}
            className="h-12 w-full rounded-pill border border-pw-stroke/60 bg-pw-input px-5 text-[15px] text-pw-ink focus:border-pw-button focus:outline-none">
            {/* suite_id is nullable, so "decide later" is a real answer rather
                than a placeholder that has to be replaced before submitting. */}
            <option value="">Decide later</option>
            {suites.map((s) => (
              <option key={s.id} value={s.id} disabled={s.occupied}>
                {s.name}
                {s.species ? ` (${s.species.toLowerCase()} only)` : " (any species)"}
                {/* THE FRACTION, NOT JUST THE VERDICT. "Kennel 1 — taken" and
                    "Kennel 1 — 2 of 2 places taken" are the same refusal, but
                    only the second tells a staff member whether the room is
                    small or the week is busy. */}
                {` — ${s.placesUsed} of ${s.capacity} taken`}
                {s.occupied ? " (full)" : ""}
              </option>
            ))}
          </select>
          {loadError && <p className="mt-1.5 text-sm text-pw-danger">{loadError}</p>}
          {!loadError && suites.length > 0 && free.length === 0 && (
            <p className="mt-1.5 text-sm text-pw-danger">
              Every suitable suite is full for these dates.
            </p>
          )}
          {!loadError && suites.length === 0 && (
            <p className="mt-1.5 text-sm text-pw-muted">
              This shelter has no suite that takes a {request?.petSpecies?.toLowerCase()}.
            </p>
          )}
        </div>

        {/* The overlap check runs inside a transaction on the server, holding a
            row lock -- two staff accepting the same pet at the same second
            cannot both win. This note exists so nobody "optimises" it into a
            plain SELECT. */}
        <p className="flex gap-2 rounded-2xl bg-pw-input px-4 py-3 text-sm text-pw-body">
          <FiShield className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
          Accepting re-checks the pet's dates and the room under a lock. If
          another staff member books either first, this request is refused
          rather than double-booked.
        </p>
      </div>
    </Modal>
  );
}

export default function BoardingPage() {
  const [tab, setTab] = useState("requests");
  const [rows, setRows] = useState([]);
  const [counts, setCounts] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [postFor, setPostFor] = useState(null);
  const [acceptFor, setAcceptFor] = useState(null);
  const [declineFor, setDeclineFor] = useState(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [listRes, countRes] = await Promise.all([
        api.get("/staff/boarding"),
        api.get("/staff/boarding/counts"),
      ]);
      setRows(listRes.data);
      setCounts(countRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load the boarding board."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [listRes, countRes] = await Promise.all([
          api.get("/staff/boarding"),
          api.get("/staff/boarding/counts"),
        ]);
        if (cancelled) return;
        setRows(listRes.data);
        setCounts(countRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the boarding board."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const of = useCallback((...statuses) => rows.filter((r) => statuses.includes(r.status)), [rows]);

  const shown = useMemo(() => {
    if (tab === "requests") return of("REQUESTED");
    if (tab === "upcoming") return of("ACCEPTED");
    if (tab === "staying") return of("ACTIVE");
    return of("COMPLETED", "DECLINED", "CANCELLED");
  }, [tab, of]);

  // ONE PAGER FOR BOTH LAYOUTS, because both render the same `shown` list --
  // cards for the two live tabs, rows for requests and history. History is the
  // one that matters: it is every stay the shelter has ever taken, and it only
  // grows. Nine, so the card grid pages in whole rows of three.
  const pager = usePaged(shown, 9, tab);

  /** Every action here is the same shape: post, reload, say what happened. */
  const [checkOutFor, setCheckOutFor] = useState(null);
  // V17. Room changes and running expenses, for a stay in progress.
  const [manageFor, setManageFor] = useState(null);

  async function act(fn, title, message) {
    setBusy(true);
    try {
      await fn();
      await load();
      setNotice({ title, message });
      return true;
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <StaffLayout
      title="Boarding"
      subtitle="Manage current stays and upcoming booking requests."
      actions={
        <Button variant="outline" size="sm" onClick={load} disabled={loading}>
          <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
        </Button>
      }
    >
      <SectionTabs tabs={STAFF_BOARDING_TABS} />

      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile icon={FiClock} value={counts?.requested ?? 0} label="Pending Requests" tone="warning" />
        <StatTile icon={FiHome} value={counts?.active ?? 0} label="Active Boardings" />
        <StatTile icon={FiLogOut} value={counts?.departingToday ?? 0} label="Departing Today" />
        <StatTile icon={FiCheck} value={counts?.completed ?? 0} label="Completed" />
      </div>

      {loadError && (
        <Card className="mt-6 px-6 py-10 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      )}

      <div className="mt-6">
        <Tabs
          value={tab} onChange={setTab} className="mb-6"
          tabs={[
            { key: "requests", label: "Requests", count: of("REQUESTED").length },
            { key: "upcoming", label: "Upcoming", count: of("ACCEPTED").length },
            { key: "staying", label: "Staying now", count: of("ACTIVE").length },
            { key: "history", label: "History",
              count: of("COMPLETED", "DECLINED", "CANCELLED").length },
          ]}
        />

        {loading ? (
          <Card className="px-6 py-16 text-center">
            <p className="text-[15px] text-pw-muted">Loading…</p>
          </Card>
        ) : shown.length === 0 ? (
          <Card className="px-6 py-16 text-center">
            <p className="text-[15px] font-medium text-pw-ink">Nothing here.</p>
          </Card>
        ) : tab === "staying" || tab === "upcoming" ? (
          <div className="grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
            {pager.shown.map((b) => {
              const elapsed = Math.min(daysSince(b.startDate), b.nights);
              const pct = b.nights > 0 ? Math.round((elapsed / b.nights) * 100) : 0;
              const lastDay = b.status === "ACTIVE" && elapsed >= b.nights;
              return (
                <Card key={b.id} className="flex h-full flex-col overflow-hidden">
                  <div className="relative aspect-[16/10] bg-pw-sidebar/25 overflow-hidden shrink-0">
                    {b.petCoverPhotoPath ? (
                      <img src={`${API_BASE}/files/${b.petCoverPhotoPath}`} alt=""
                        className="absolute inset-0 h-full w-full object-cover" />
                    ) : (
                      <span className="flex h-full w-full items-center justify-center">
                        <FiImage className="h-6 w-6 text-pw-stroke" />
                      </span>
                    )}
                    <span className={`absolute right-3 top-3 rounded-pill px-2.5 py-1 text-[11px] font-semibold ${
                      b.status === "ACCEPTED" ? "bg-pw-input text-pw-button"
                      : lastDay ? "bg-amber-100 text-amber-800" : "bg-green-100 text-green-800"}`}>
                      {b.status === "ACCEPTED" ? "Not arrived"
                        : lastDay ? "Last Day" : `Day ${elapsed} / ${b.nights}`}
                    </span>
                  </div>

                  <div className="flex flex-1 flex-col p-5">
                    <div className="flex items-start justify-between gap-2">
                      <h3 className="text-lg font-semibold leading-tight">{b.petName}</h3>
                      <span className="rounded-pill bg-pw-input px-2.5 py-1 text-xs text-pw-button">
                        {b.suiteName ?? "No suite"}
                      </span>
                    </div>
                    <p className="mt-1 text-sm text-pw-muted">Owner: {b.ownerName}</p>
                    <p className="mt-1.5 flex items-center gap-1.5 text-sm text-pw-muted">
                      <FiCalendar className="h-3.5 w-3.5" /> {day(b.startDate)} – {day(b.endDate)}
                    </p>

                    {/* V17. A ROOM IS NOT A ONE-TIME DECISION, AND A BILL IS
                        NOT A MEMORY TEST. The suite used to be assigned once
                        at accept() and never again, and expenses could only be
                        typed into the check-out dialog on the last day. Both
                        live here now, in one dialog, because they are the same
                        walk down the corridor. */}
                    {b.status === "ACTIVE" && (
                      <button type="button"
                        className="mt-2 self-start text-sm font-medium text-pw-button hover:underline"
                        onClick={() => setManageFor(b)}>
                        Change room or add an expense
                      </button>
                    )}

                    {b.status === "ACTIVE" && (
                      <div className="mt-4">
                        <div className="flex items-center justify-between text-xs">
                          <span className="text-pw-muted">Progress</span>
                          <span className="tabular-nums font-medium text-pw-ink">{pct}%</span>
                        </div>
                        <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-pw-input">
                          <div className="h-full rounded-full bg-pw-button" style={{ width: `${pct}%` }} />
                        </div>
                      </div>
                    )}

                    <div className="mt-auto flex gap-3 pt-5">
                      {b.status === "ACCEPTED" ? (
                        <Button size="sm" full disabled={busy}
                          onClick={() => act(() => api.post(`/staff/boarding/${b.id}/check-in`),
                            "Checked in",
                            `${b.petName} is marked as staying, and the owner's timeline now shows the arrival.`)}>
                          <FiLogIn className="h-4 w-4" /> Check in
                        </Button>
                      ) : (
                        <>
                          <Button variant="outline" size="sm" full onClick={() => setPostFor(b)}>
                            <FiCamera className="h-4 w-4" /> Update
                          </Button>
                          {/* The bill is written at check-out and cannot be
                              amended afterwards, so the modal is where any
                              medication or extra food gets added. */}
                          <Button size="sm" full disabled={busy}
                            onClick={() => setCheckOutFor(b)}>
                            <FiLogOut className="h-4 w-4" /> Check out
                          </Button>
                        </>
                      )}
                    </div>
                  </div>
                </Card>
              );
            })}
          </div>
        ) : (
          <div className="space-y-4">
            {pager.shown.map((r) => (
              <Card key={r.id} className="flex flex-wrap items-center gap-5 p-5">
                <span className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full bg-pw-input text-sm font-semibold text-pw-button">
                  {r.petCoverPhotoPath
                    ? <img src={`${API_BASE}/files/${r.petCoverPhotoPath}`} alt=""
                        className="h-full w-full object-cover" />
                    : r.petName.slice(0, 2)}
                </span>

                <div className="min-w-[180px] flex-1">
                  <p className="text-[15px] font-semibold text-pw-ink">{r.petName}</p>
                  <p className="mt-0.5 text-sm text-pw-muted">Owner: {r.ownerName}</p>
                </div>

                <div className="min-w-[170px]">
                  <p className="text-[11px] font-semibold uppercase tracking-wider text-pw-muted">Dates</p>
                  <p className="mt-0.5 text-sm text-pw-ink">
                    {day(r.startDate)} – {day(r.endDate)} · {r.nights} night{r.nights === 1 ? "" : "s"}
                  </p>
                </div>

                <div className="min-w-[150px]">
                  <p className="text-[11px] font-semibold uppercase tracking-wider text-pw-muted">Emergency vet</p>
                  <p className={`mt-0.5 inline-flex items-center gap-1.5 text-sm font-medium ${
                    r.vetCareAuthorised ? "text-green-700" : "text-rose-700"}`}>
                    {r.vetCareAuthorised ? <FiCheck className="h-4 w-4" /> : <FiX className="h-4 w-4" />}
                    {r.vetCareAuthorised ? "Authorised" : "Not authorised"}
                  </p>
                </div>

                {tab === "requests" ? (
                  <div className="ml-auto flex gap-3">
                    <Button variant="outline" size="sm" onClick={() => setDeclineFor(r)}>Decline</Button>
                    <Button size="sm" onClick={() => setAcceptFor(r)}>Accept</Button>
                  </div>
                ) : (
                  <div className="ml-auto text-right">
                    <span className="rounded-pill bg-pw-input px-3 py-1 text-[11px] font-semibold text-pw-muted">
                      {r.status}
                    </span>
                    {r.declineMessage && (
                      <p className="mt-1.5 max-w-[280px] text-xs text-pw-muted">{r.declineMessage}</p>
                    )}
                  </div>
                )}
              </Card>
            ))}
          </div>
        )}

        {/* Outside the two branches, so one pager serves both layouts. In its
            own card because the cards above it are separate boxes with nothing
            for a hairline to divide. */}
        {pager.paged && (
          <Card className="mt-5 overflow-hidden">
            <Pagination {...pager.props} bordered={false} noun="stays" />
          </Card>
        )}
      </div>

      <PostBoardingUpdateModal
        open={!!postFor}
        boarding={postFor}
        busy={busy}
        onClose={() => setPostFor(null)}
        onPost={async (u) => {
          // multipart, because the photo rides along: one request that either
          // happens or does not, rather than a note that lands and a picture
          // that fails separately.
          const body = new FormData();
          body.append("updateType", u.type);
          body.append("note", u.note);
          if (u.photo?.[0]) body.append("photo", u.photo[0]);
          const ok = await act(
            () => api.post(`/staff/boarding/${u.boardingId}/updates`, body),
            "Update posted", "It is on the owner's boarding timeline now.");
          if (ok) setPostFor(null);
        }}
      />

      <DeclineBoardingModal
        open={!!declineFor}
        request={declineFor}
        busy={busy}
        onClose={() => setDeclineFor(null)}
        onDecline={async (d) => {
          // The reason and the sentence are joined into one message on
          // purpose: boarding_requests has no decline_reason_code column --
          // V1 gave adoption applications one and boarding none -- so
          // inventing a second field here would mean the code was stored
          // nowhere and silently dropped.
          const ok = await act(
            () => api.post(`/staff/boarding/${d.id}/decline`,
              { message: [d.reason, d.message].filter(Boolean).join(" — ") }),
            "Request declined",
            `${d.ownerName ?? "The owner"} has been told why. The dates are free again.`);
          if (ok) setDeclineFor(null);
        }}
      />

      <AcceptModal
        open={!!acceptFor}
        request={acceptFor}
        busy={busy}
        onClose={() => setAcceptFor(null)}
        onAccept={async (suiteId) => {
          const ok = await act(
            () => api.post(`/staff/boarding/${acceptFor.id}/accept`, { suiteId }),
            "Request accepted",
            `${acceptFor.petName}'s dates are held. Check them in when they arrive.`);
          if (ok) setAcceptFor(null);
        }}
      />

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? ""}
      />
      <StayManageModal
        // Keyed, so opening a second stay starts from its own data rather
        // than the previous one's until the fetch lands.
        key={manageFor?.id ?? "none"}
        open={manageFor != null}
        stay={manageFor}
        onClose={() => setManageFor(null)}
        onChanged={load}
      />

      <CheckOutModal
        open={checkOutFor != null}
        stay={checkOutFor}
        busy={busy}
        onClose={() => setCheckOutFor(null)}
        onConfirm={async (extras) => {
          const stay = checkOutFor;
          const ok = await act(
            () => api.post(`/staff/boarding/${stay.id}/complete`, { extras }),
            "Checked out",
            `${stay.petName} has gone home. The bill has been issued and the room is free again.`);
          if (ok) setCheckOutFor(null);
        }}
      />
    </StaffLayout>
  );
}
