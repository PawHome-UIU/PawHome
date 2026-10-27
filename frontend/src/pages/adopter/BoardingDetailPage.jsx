import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  FiArrowLeft, FiCheck, FiAward, FiPlay, FiFlag, FiMapPin, FiPhone,
  FiCoffee, FiHeart, FiActivity, FiPlusSquare, FiLogIn, FiLogOut, FiCalendar,
  FiXCircle, FiHome,
} from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import SuccessModal from "../../components/ui/SuccessModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * One stay, as the owner sees it.
 *
 * THE UPDATE FEED IS THE POINT OF THE PAGE. boarding_updates is append-only by
 * design -- there is no edit and no delete behind it -- so a note the owner
 * read on Tuesday still says on Friday what it said, and the timeline is a
 * record rather than a status board that can be quietly rewritten.
 *
 * THE RAIL ONLY MAKES SENSE FOR A STAY THAT IS STILL GOING. Requested,
 * accepted, active and completed are four points on one line; declined and
 * cancelled are that line stopping, so they get a plain statement instead of a
 * progress bar pretending to be three-quarters finished.
 */
const STAGES = [
  { key: "REQUESTED", label: "Requested", icon: FiCheck },
  { key: "ACCEPTED", label: "Accepted", icon: FiAward },
  { key: "ACTIVE", label: "Active", icon: FiPlay },
  { key: "COMPLETED", label: "Completed", icon: FiFlag },
];

const INSTRUCTION_LABEL = {
  DIETARY: "Dietary needs",
  MEDICATION: "Medication",
  COMFORT_PLAY: "Comfort & play",
  EXERCISE: "Exercise",
  OTHER: "Other",
};

// SEVEN SINCE V18. ROOM_CHANGE is written by the shelter moving the pet, not
// posted by hand -- see BoardingService.postUpdate, which refuses it along
// with arrival and departure.
const UPDATE_ICON = {
  ARRIVAL_CHECK_IN: FiLogIn, FEEDING: FiCoffee, PLAYTIME: FiActivity,
  MEDICATION: FiPlusSquare, HEALTH: FiHeart, DEPARTURE: FiLogOut,
  ROOM_CHANGE: FiHome,
};

const UPDATE_TITLE = {
  ARRIVAL_CHECK_IN: "Arrival check-in", FEEDING: "Feeding", PLAYTIME: "Playtime",
  MEDICATION: "Medication", HEALTH: "Health", DEPARTURE: "Departure",
  ROOM_CHANGE: "Moved room",
};

// LocalDate: parsed locally, never through new Date("2026-09-04"), which is
// UTC midnight and renders as the day before anywhere behind UTC.
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

// LocalDateTime, which does carry a time, so the plain constructor is right.
const when = (iso) => iso && new Date(iso).toLocaleString(undefined,
  { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });

export default function BoardingDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const res = await api.get(`/boarding/${id}`);
      setData(res.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load that stay."));
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get(`/boarding/${id}`);
        if (!cancelled) setData(res.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load that stay."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  async function cancel() {
    setBusy(true);
    try {
      await api.post(`/boarding/${id}/cancel`);
      await load();
      setNotice({
        title: "Stay cancelled",
        message: "The shelter can see it was called off rather than simply gone, "
               + "and the suite goes back into their pool.",
      });
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <AdopterLayout>
        <Card className="px-6 py-20 text-center">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </Card>
      </AdopterLayout>
    );
  }

  if (loadError || !data) {
    return (
      <AdopterLayout>
        <Card className="px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-danger">
            {loadError || "That stay could not be found."}
          </p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      </AdopterLayout>
    );
  }

  const b = data.request;
  const stopped = b.status === "DECLINED" || b.status === "CANCELLED";
  const stage = STAGES.findIndex((s) => s.key === b.status);
  const canCancel = b.status === "REQUESTED" || b.status === "ACCEPTED";

  return (
    <AdopterLayout>
      <button onClick={() => navigate("/app/boarding")}
        className="mb-5 inline-flex items-center gap-2 text-sm font-medium text-pw-button hover:underline">
        <FiArrowLeft className="h-4 w-4" /> Boarding Details: {b.petName}
      </button>

      <Card className="relative overflow-hidden">
        <div className="relative aspect-[21/9] bg-pw-sidebar/30 overflow-hidden shrink-0">
          {b.petCoverPhotoPath && (
            <img src={`${API_BASE}/files/${b.petCoverPhotoPath}`} alt=""
              className="absolute inset-0 h-full w-full object-cover" />
          )}
        </div>
        {/* Scrim, so the white heading holds against a bright photo and against
            the pale placeholder alike. A drop-shadow survives neither. */}
        <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-pw-ink/75 to-transparent" />
        <div className="absolute bottom-5 left-6">
          <span className="rounded-pill bg-pw-button px-3 py-1 text-xs font-semibold text-white">
            {b.petBreed}
          </span>
          <h1 className="mt-2 text-4xl font-semibold text-white">{b.petName}</h1>
          <p className="mt-1 flex items-center gap-1.5 text-sm text-white/95">
            <FiCalendar className="h-4 w-4" /> {day(b.startDate)} – {day(b.endDate)}
          </p>
        </div>
      </Card>

      {stopped ? (
        <Card className="mt-6 p-7">
          <p className="flex items-center gap-2 text-[15px] font-semibold text-pw-ink">
            <FiXCircle className="h-5 w-5 text-pw-danger" />
            {b.status === "DECLINED"
              ? `${b.shelterName} could not take ${b.petName} on these dates.`
              : `You cancelled this stay${b.reviewedAt ? ` on ${when(b.reviewedAt)}` : ""}.`}
          </p>
          {/* The sentence the shelter wrote. Hiding it behind "Declined" would
              leave the owner with no idea what to change before asking again. */}
          {b.declineMessage && (
            <p className="mt-3 rounded-card bg-rose-50 px-4 py-3 text-[15px] leading-relaxed text-pw-body">
              {b.declineMessage}
            </p>
          )}
          <Button to="/app/boarding" size="sm" className="mt-5">Back to boarding</Button>
        </Card>
      ) : (
        <Card className="mt-6 p-7">
          <ol className="flex items-start justify-between gap-2">
            {STAGES.map((s, i) => {
              const Icon = s.icon;
              const done = i <= stage;
              const current = i === stage;
              return (
                <li key={s.key} className="relative flex flex-1 flex-col items-center text-center">
                  {i > 0 && (
                    <span aria-hidden="true"
                      className={`absolute right-1/2 top-5 -z-0 h-0.5 w-full ${i <= stage ? "bg-pw-button" : "bg-pw-stroke/50"}`} />
                  )}
                  <span className={`relative z-10 flex h-10 w-10 items-center justify-center rounded-full ${
                    current ? "bg-pw-button text-white ring-4 ring-pw-input"
                    : done ? "bg-pw-button text-white" : "bg-pw-input text-pw-muted"}`}>
                    <Icon className="h-4 w-4" />
                  </span>
                  <span className={`mt-2.5 text-sm ${current ? "font-semibold text-pw-button" : done ? "text-pw-body" : "text-pw-muted"}`}>
                    {s.label}
                  </span>
                </li>
              );
            })}
          </ol>

          <dl className="mt-7 grid gap-4 border-t border-pw-stroke/40 pt-5 text-sm sm:grid-cols-3">
            <div>
              <dt className="text-pw-muted">Nights</dt>
              <dd className="mt-0.5 text-pw-ink">{b.nights}</dd>
            </div>
            <div>
              <dt className="text-pw-muted">Suite</dt>
              <dd className="mt-0.5 text-pw-ink">{b.suiteName ?? "Not assigned yet"}</dd>
            </div>
            <div>
              <dt className="text-pw-muted">Estimated cost</dt>
              <dd className="mt-0.5 tabular-nums text-pw-ink">
                ৳{Number(b.estimatedCost ?? 0).toFixed(2)}
                <span className="text-pw-muted">
                  {" "}(৳{Number(b.dailyRate ?? 0).toFixed(2)}/night)
                </span>
              </dd>
            </div>
          </dl>

          {canCancel && (
            <Button variant="outline" size="sm" className="mt-6 !border-pw-danger !text-pw-danger"
              disabled={busy} onClick={cancel}>
              {busy ? "Cancelling…" : "Cancel this stay"}
            </Button>
          )}
        </Card>
      )}

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card className="p-6 sm:p-7">
            <h2 className="flex items-center gap-2 text-lg font-semibold text-pw-button">
              <FiPlusSquare className="h-5 w-5" /> Special Care Instructions
            </h2>
            {data.instructions.length === 0 ? (
              <p className="mt-4 text-[15px] text-pw-muted">
                You did not leave any care notes with this request.
              </p>
            ) : (
              <div className="mt-5 space-y-4">
                {data.instructions.map((ins) => (
                  <div key={ins.id} className="rounded-2xl bg-pw-input p-5">
                    <p className="text-[11px] font-semibold uppercase tracking-wider text-pw-button">
                      {INSTRUCTION_LABEL[ins.category] ?? ins.category}
                    </p>
                    <p className="mt-1.5 text-[15px] leading-relaxed text-pw-ink">{ins.body}</p>
                  </div>
                ))}
              </div>
            )}
          </Card>

          <Card className="p-6 sm:p-7">
            <h2 className="flex items-center gap-2 text-lg font-semibold text-pw-button">
              <FiMapPin className="h-5 w-5" /> Shelter Contact
            </h2>
            <div className="mt-5">
              <div>
                <p className="text-[15px] font-semibold text-pw-ink">{b.shelterName}</p>
                <p className="mt-1 text-sm text-pw-muted">{b.shelterAddress}</p>
                <p className="mt-1.5 flex items-center gap-1.5 text-sm text-pw-button">
                  <FiPhone className="h-3.5 w-3.5" /> {b.shelterPhone}
                </p>
              </div>
              {/* THE "MESSAGE SHELTER" BUTTON IS GONE, not disabled. There is
                  no messages table in the schema and no design for one, so it
                  was a control for a feature nobody had decided on -- and a
                  greyed-out button still promises the feature is coming. The
                  phone number above is how you reach them. */}
            </div>
            <p className="mt-5 border-t border-pw-stroke/40 pt-4 text-sm text-pw-muted">
              Emergency contact on this booking: <span className="text-pw-body">{b.emergencyContact}</span>.
              {" "}Vet care {b.vetCareAuthorised ? "is" : "is not"} authorised.
            </p>
          </Card>
        </div>

        <Card className="flex flex-col p-6">
          <h2 className="text-lg font-semibold leading-tight">Health &amp; Care Updates</h2>

          {data.updates.length === 0 ? (
            <p className="mt-4 text-[15px] text-pw-muted">
              {b.status === "ACTIVE"
                ? "Nothing posted yet today."
                : "Updates start once the pet is checked in."}
            </p>
          ) : (
            <ol className="mt-5 space-y-0">
              {data.updates.map((u, i) => {
                const Icon = UPDATE_ICON[u.updateType] ?? FiHeart;
                const last = i === data.updates.length - 1;
                return (
                  <li key={u.id} className="flex gap-3.5">
                    <div className="flex flex-col items-center">
                      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-pw-input text-pw-button">
                        <Icon className="h-3.5 w-3.5" />
                      </span>
                      {!last && <span className="my-1 w-px flex-1 bg-pw-stroke/60" />}
                    </div>
                    <div className={last ? "" : "pb-6"}>
                      <div className="flex flex-wrap items-baseline gap-x-2">
                        <p className="text-[15px] font-semibold text-pw-ink">
                          {UPDATE_TITLE[u.updateType] ?? u.updateType}
                        </p>
                        <p className="text-xs text-pw-muted">{when(u.postedAt)}</p>
                      </div>
                      <p className="mt-1 text-sm leading-relaxed text-pw-body">{u.note}</p>
                      {u.photoPath ? (
                        <img src={`${API_BASE}/files/${u.photoPath}`} alt=""
                          className="mt-3 aspect-[4/3] w-full rounded-2xl object-cover" />
                      ) : null}
                      <p className="mt-1.5 text-xs text-pw-muted">{u.postedByName}</p>
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </Card>
      </div>

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? ""}
      />
    </AdopterLayout>
  );
}
