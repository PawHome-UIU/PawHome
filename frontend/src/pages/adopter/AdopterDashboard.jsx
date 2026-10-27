import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  FiFileText, FiHeart, FiStar, FiHome, FiCalendar, FiInfo, FiTarget,
} from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import MetricTile from "../../components/adopter/MetricTile";
import MatchCard from "../../components/adopter/MatchCard";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import { useAuth } from "../../context/AuthContext";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The adopter's landing page: four counts and the top of three lists.
 *
 * FOUR REQUESTS, NOT A SUMMARY ENDPOINT. Three of the four are needed for
 * their CONTENT and not merely their length -- the best matches, the running
 * applications, the next stay -- so a dashboard-shaped projection on the
 * server would be a fifth thing to keep in step with the four it summarises,
 * in exchange for saving one list fetched purely to be counted.
 *
 * NOTHING HERE IS INVENTED. Every number and every line of text below is
 * derived from a row that exists; where there is no row there is an empty
 * state saying so. A dashboard is the screen people trust most and check
 * least, which is exactly why a plausible-looking placeholder does the most
 * damage here.
 */

/**
 * The three points of an application's life, drawn from its real status.
 *
 * THE STATUSES ARE THE SPEC. adoption_applications.status is one of PENDING,
 * APPROVED, REJECTED and WITHDRAWN -- there is no "step" column and no phase
 * name, so the rail maps those four onto three points rather than inventing a
 * workflow the database does not model.
 */
const RAIL = ["Submitted", "Under review", "Decision"];

const PHASE = {
  PENDING: { label: "Under review", lit: 2, tone: "bg-pw-button", text: "text-pw-button" },
  APPROVED: { label: "Approved", lit: 3, tone: "bg-green-600", text: "text-green-700" },
  REJECTED: { label: "Declined", lit: 3, tone: "bg-pw-danger", text: "text-pw-danger" },
  WITHDRAWN: { label: "Withdrawn", lit: 1, tone: "bg-pw-muted", text: "text-pw-muted" },
};

const BADGE = {
  PENDING: "bg-amber-50 text-amber-700",
  APPROVED: "bg-green-50 text-green-700",
  REJECTED: "bg-rose-50 text-pw-danger",
  WITHDRAWN: "bg-pw-input text-pw-muted",
};

function ApplicationProgress({ status }) {
  const phase = PHASE[status] ?? PHASE.PENDING;
  return (
    <div>
      <div className="flex items-center justify-between gap-3">
        <span className={`text-sm font-medium ${phase.text}`}>{phase.label}</span>
        <span className="text-xs text-pw-muted">Step {phase.lit} of {RAIL.length}</span>
      </div>
      <div className="mt-2 flex gap-1.5">
        {RAIL.map((s, i) => (
          <span key={s}
            className={`h-1.5 flex-1 rounded-full ${i < phase.lit ? phase.tone : "bg-pw-input"}`} />
        ))}
      </div>
      <div className="mt-1.5 flex justify-between">
        {RAIL.map((s, i) => (
          <span key={s} className={`text-[10px] uppercase tracking-wide ${
            i < phase.lit ? `font-semibold ${phase.text}` : "text-pw-muted"}`}>
            {s}
          </span>
        ))}
      </div>
    </div>
  );
}

// LocalDateTime carries a time, so the plain constructor is right here.
const when = (iso) => iso && new Date(iso).toLocaleDateString(undefined,
  { year: "numeric", month: "short", day: "numeric" });

// A LocalDate is "2026-10-01" -- new Date() would read that as UTC midnight,
// which anywhere behind UTC renders as the day before.
function localDate(iso) {
  if (!iso) return null;
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

const day = (iso) => localDate(iso)?.toLocaleDateString(undefined,
  { year: "numeric", month: "short", day: "numeric" }) ?? "";

/** Whole days from today to a LocalDate, negative once it has passed. */
function daysUntil(iso) {
  const target = localDate(iso);
  if (!target) return 0;
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((target - today) / 86400000);
}

export default function AdopterDashboard() {
  const { user } = useAuth();

  const [pets, setPets] = useState([]);
  const [apps, setApps] = useState([]);
  const [favorites, setFavorites] = useState([]);
  const [stays, setStays] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [savingId, setSavingId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [petRes, appRes, favRes, stayRes] = await Promise.all([
        api.get("/pets"),
        api.get("/applications/mine"),
        api.get("/favorites"),
        api.get("/boarding/mine"),
      ]);
      setPets(petRes.data);
      setApps(appRes.data);
      setFavorites(favRes.data);
      setStays(stayRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your dashboard."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [petRes, appRes, favRes, stayRes] = await Promise.all([
          api.get("/pets"),
          api.get("/applications/mine"),
          api.get("/favorites"),
          api.get("/boarding/mine"),
        ]);
        if (cancelled) return;
        setPets(petRes.data);
        setApps(appRes.data);
        setFavorites(favRes.data);
        setStays(stayRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your dashboard."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  async function toggleSave(pet) {
    setSavingId(pet.id);
    const next = !pet.favorite;
    try {
      if (next) await api.put(`/favorites/${pet.id}`);
      else await api.delete(`/favorites/${pet.id}`);
      setPets((list) => list.map((p) => (p.id === pet.id ? { ...p, favorite: next } : p)));
      // The tile counts favourites, so it has to move with the heart.
      setFavorites((list) => next
        ? [...list, pet]
        : list.filter((p) => p.id !== pet.id));
    } catch {
      // Quiet: the heart stays as the server last reported it.
    } finally {
      setSavingId(null);
    }
  }

  // matchPercent is null for everyone until the personality quiz is sat -- the
  // server only scores when v_adopter_traits has something to compare against.
  const ranked = useMemo(
    () => pets.filter((p) => p.matchPercent != null)
              .sort((a, b) => b.matchPercent - a.matchPercent),
    [pets]);

  // TWO DIFFERENT COUNTS, and the difference matters. Once the quiz is sat
  // every available pet gets a percentage, most of them zero -- so `ranked` is
  // "pets we could score" and would make the tile read 7 when 5 of those share
  // no trait at all. A nought per cent match is not a recommendation.
  const matched = ranked.filter((p) => p.matchPercent > 0);

  const running = apps.filter((a) => a.status === "PENDING");

  /** The stay that is actually coming, or happening. Soonest first. */
  const nextStay = useMemo(() => stays
    .filter((b) => b.status === "ACCEPTED" || b.status === "ACTIVE")
    .sort((a, b) => a.startDate.localeCompare(b.startDate))[0] ?? null,
    [stays]);

  const firstName = user?.fullName?.trim().split(/\s+/)[0];

  return (
    <AdopterLayout
      title={firstName ? `Welcome back, ${firstName}!` : "Welcome back!"}
      subtitle="Your next companion might be just a click away."
      actions={
        <>
          <Button size="sm" variant="outline" to="/app/recommendations">View Recommendations</Button>
          <Button size="sm" to="/app/pets">Browse Pets</Button>
        </>
      }
    >
      {loadError && (
        <Card className="mb-8 px-6 py-10 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      )}

      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        <MetricTile icon={FiFileText} label="Active Applications"
          value={running.length} to="/app/applications" />
        <MetricTile icon={FiHeart} label="Favorites Saved"
          value={favorites.length} to="/app/favorites" />
        <MetricTile icon={FiStar} label="Recommended Matches"
          value={matched.length} to="/app/recommendations" />
        <MetricTile icon={FiHome} label="Active Boardings"
          value={stays.filter((b) => b.status === "ACCEPTED" || b.status === "ACTIVE").length}
          to="/app/boarding" />
      </div>

      <section className="mt-10">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h2 className="text-2xl font-semibold">Recommended for you</h2>
          <Link to="/app/recommendations" className="text-sm font-medium text-pw-button hover:underline">
            Explore all matches
          </Link>
        </div>

        {loading ? (
          <Card className="mt-6 px-6 py-14 text-center">
            <p className="text-[15px] text-pw-muted">Working out your matches…</p>
          </Card>
        ) : ranked.length === 0 ? (
          // No score at all means no personality attempt, not "no good pets".
          // Saying the second would be a lie the quiz can fix in two minutes.
          <Card className="mt-6 px-6 py-12 text-center">
            <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-pw-input text-pw-button">
              <FiTarget className="h-6 w-6" />
            </span>
            <p className="mt-4 text-[15px] font-semibold text-pw-ink">
              Take the personality quiz to see matches.
            </p>
            <p className="mt-1.5 text-sm text-pw-muted">
              Your answers become a set of traits, and the percentages compare
              each pet against those.
            </p>
            <Button to="/app/recommendations" size="sm" className="mt-6">Start the quiz</Button>
          </Card>
        ) : (
          <div className="mt-6 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {ranked.slice(0, 3).map((p) => (
              <MatchCard key={p.id} pet={p} busy={savingId === p.id} onToggleSave={toggleSave} />
            ))}
          </div>
        )}
      </section>

      <div className="mt-10 grid gap-6 lg:grid-cols-2">
        <Card className="p-6">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">My Applications</h2>
            <Link to="/app/applications" className="text-sm font-medium text-pw-button hover:underline">
              View all
            </Link>
          </div>

          {!loading && apps.length === 0 ? (
            <div className="mt-5 rounded-card border border-dashed border-pw-stroke bg-pw-input/50 px-6 py-8 text-center">
              <p className="text-[15px] font-semibold text-pw-ink">No applications yet.</p>
              <p className="mt-1 text-sm text-pw-muted">
                The apply button is on any pet's profile.
              </p>
              <Button to="/app/pets" size="sm" variant="subtle" className="mt-5">Browse Pets</Button>
            </div>
          ) : (
            <ul className="mt-5 space-y-5">
              {apps.slice(0, 3).map((a) => (
                <li key={a.id} className="flex gap-4">
                  <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-pw-input text-sm font-semibold text-pw-button">
                    {a.petName.slice(0, 2)}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div>
                        <p className="text-[15px] font-medium text-pw-ink">{a.petName}</p>
                        <p className="text-xs italic text-pw-muted">Applied {when(a.submittedAt)}</p>
                      </div>
                      <span className={`rounded-pill px-2.5 py-1 text-[11px] font-semibold ${BADGE[a.status]}`}>
                        {a.status}
                      </span>
                    </div>
                    <div className="mt-3"><ApplicationProgress status={a.status} /></div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card className="p-6">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">Upcoming Boarding</h2>
            <Link to="/app/boarding" className="text-sm font-medium text-pw-button hover:underline">
              View all
            </Link>
          </div>

          {!nextStay ? (
            <div className="mt-5 rounded-card border border-dashed border-pw-stroke bg-pw-input/50 px-6 py-8 text-center">
              <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-white text-pw-button">
                <FiCalendar className="h-6 w-6" />
              </span>
              <p className="mt-4 text-[15px] font-semibold text-pw-ink">No stay booked.</p>
              <p className="mt-1 text-sm text-pw-muted">
                Boarding is available for pets you have adopted.
              </p>
              <Button to="/app/boarding" size="sm" variant="subtle" className="mt-5">
                Request boarding
              </Button>
            </div>
          ) : (
            <>
              <div className="mt-5 rounded-card border border-dashed border-pw-stroke bg-pw-input/50 px-6 py-8 text-center">
                <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-white text-pw-button">
                  <FiCalendar className="h-6 w-6" />
                </span>
                <p className="mt-4 text-[15px] font-semibold text-pw-ink">
                  Stay with {nextStay.petName}
                </p>
                <p className="mt-1 text-sm text-pw-body">
                  {day(nextStay.startDate)} – {day(nextStay.endDate)}
                </p>
                <p className="text-sm text-pw-muted">
                  {nextStay.shelterName}
                  {nextStay.suiteName && ` · ${nextStay.suiteName}`}
                </p>
                <Button to={`/app/boarding/${nextStay.id}`} size="sm" variant="subtle" className="mt-5">
                  View Details
                </Button>
              </div>

              {/* Derived from the booking, never a standing reminder about a
                  feature that does not exist. Every branch below is readable
                  off the row itself. */}
              <p className="mt-4 flex gap-2.5 rounded-2xl bg-pw-input px-4 py-3 text-sm text-pw-body">
                <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
                <span>
                  {nextStay.status === "ACTIVE"
                    ? `${nextStay.petName} is staying now — collection on ${day(nextStay.endDate)}.`
                    : (() => {
                        const n = daysUntil(nextStay.startDate);
                        if (n > 1) return `Drop-off in ${n} days.`;
                        if (n === 1) return "Drop-off is tomorrow.";
                        if (n === 0) return "Drop-off is today.";
                        // Accepted, start date passed, nobody checked in.
                        return "Drop-off was due — call the shelter if plans have changed.";
                      })()}
                  {!nextStay.vetCareAuthorised
                    && " Emergency vet care is not authorised on this booking."}
                  {!nextStay.suiteName
                    && " The shelter has not assigned a suite yet."}
                </span>
              </p>
            </>
          )}
        </Card>
      </div>
    </AdopterLayout>
  );
}
