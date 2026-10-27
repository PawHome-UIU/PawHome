import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { FiPlus, FiCalendar, FiHome, FiImage, FiRefreshCw } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import SuccessModal from "../../components/ui/SuccessModal";
import BoardingQuizModal from "../../components/adopter/BoardingQuizModal";
import BoardingRequestModal from "../../components/adopter/BoardingRequestModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";
import SectionTabs from "../../components/portal/SectionTabs";
import { ADOPTER_BOARDING_TABS } from "../../lib/navTabs";

/**
 * Boarding, from the owner's side.
 *
 * BOOKING IS TWO STEPS AND THAT IS DELIBERATE. The quiz is the BOARDING half
 * of the shared question bank -- editable by an admin, answered into
 * quiz_attempts -- and the form after it is the booking's own columns and its
 * care instructions. Collapsing them into one wizard is what the old mock did,
 * and it left the dates and the emergency contact looking like quiz answers
 * when they are neither.
 *
 * THE QUIZ ATTEMPT IS CARRIED BETWEEN THE TWO. fk_boarding_attempt is
 * composite -- (quiz_attempt_id, quiz_attempt_type) against
 * quiz_attempts (id, quiz_type) -- so the id handed over here can only ever be
 * attached to this request as a BOARDING attempt.
 */
const STATUS_TONE = {
  REQUESTED: "bg-amber-50 text-amber-700",
  ACCEPTED: "bg-pw-input text-pw-button",
  ACTIVE: "bg-sky-50 text-sky-700",
  COMPLETED: "bg-green-50 text-green-700",
  DECLINED: "bg-rose-50 text-rose-700",
  CANCELLED: "bg-pw-input text-pw-muted",
};

// A LocalDate is "2026-09-04" -- no time and no zone. new Date() on that parses
// it as UTC midnight, which anywhere behind UTC renders as the day before.
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

export default function AdopterBoardingPage() {
  const [pets, setPets] = useState([]);
  const [stays, setStays] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  // The booking flow, as three nullable slots rather than a step counter: the
  // pet is carried the whole way, so neither modal can open without knowing
  // whose boarding this is.
  const [quizFor, setQuizFor] = useState(null);
  const [formFor, setFormFor] = useState(null);   // { pet, attemptId }
  const [done, setDone] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [petRes, stayRes] = await Promise.all([
        api.get("/my-pets"),
        api.get("/boarding/mine"),
      ]);
      setPets(petRes.data);
      setStays(stayRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your boarding."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [petRes, stayRes] = await Promise.all([
          api.get("/my-pets"),
          api.get("/boarding/mine"),
        ]);
        if (cancelled) return;
        setPets(petRes.data);
        setStays(stayRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your boarding."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const only = pets.length === 1 ? pets[0] : null;

  return (
    <AdopterLayout
      title="Pet Boarding"
      subtitle="Leave your adopted pet in safe hands while you travel."
      actions={
        <div className="flex items-center gap-3">
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          <Button
            size="sm"
            disabled={pets.length === 0}
            // With one pet there is nothing to choose, so skip straight to the
            // quiz. With several, the per-pet buttons below are the picker --
            // a second chooser in a modal would be the same question twice.
            onClick={() => (only ? setQuizFor(only) : document
              .getElementById("my-adopted-pets")
              ?.scrollIntoView({ behavior: "smooth", block: "start" }))}
          >
            <FiPlus className="h-4 w-4" /> New Boarding Request
          </Button>
        </div>
      }
    >
      <SectionTabs tabs={ADOPTER_BOARDING_TABS} />

      {loadError && (
        <Card className="mb-8 px-6 py-10 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      )}

      <section id="my-adopted-pets">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="flex items-center gap-2 text-lg font-semibold">
            <FiHome className="h-4 w-4 text-pw-button" /> My Pets
          </h2>
          <Link to="/app/my-pets" className="text-sm font-medium text-pw-button hover:underline">
            Manage my pets
          </Link>
        </div>

        {/* Boarding is only ever offered for pets this account already owns --
            the server filters on pets.owner_id, and the catalogue is not shown
            here at all, because booking a stay for a pet you do not own is not
            a thing the system permits. */}
        {loading ? (
          <Card className="mt-5 px-6 py-14 text-center">
            <p className="text-[15px] text-pw-muted">Loading…</p>
          </Card>
        ) : pets.length === 0 ? (
          <Card className="mt-5 px-6 py-14 text-center">
            <p className="text-[15px] font-medium text-pw-ink">No pets on your list yet.</p>
            <p className="mt-1.5 text-sm text-pw-muted">
              Boarding is for an animal you own — one adopted through PawHome,
              or one you already had at home.
            </p>
            <div className="mt-6 flex justify-center gap-3">
              <Button to="/app/my-pets" size="sm">Add a pet from home</Button>
              <Button to="/app/pets" size="sm" variant="outline">Browse Pets</Button>
            </div>
          </Card>
        ) : (
          <div className="mt-5 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {pets.map((p) => (
              <Card key={p.id} className="flex h-full flex-col overflow-hidden">
                <div className="relative aspect-[16/10] bg-pw-sidebar/25 overflow-hidden shrink-0">
                  {p.coverPhotoPath ? (
                    <img src={`${API_BASE}/files/${p.coverPhotoPath}`} alt=""
                      className="absolute inset-0 h-full w-full object-cover" />
                  ) : (
                    <span className="flex h-full w-full items-center justify-center">
                      <FiImage className="h-6 w-6 text-pw-stroke" />
                    </span>
                  )}
                  {/* Three possibilities since V9, not two: a pet registered
                      from home is OWNED and was never adopted by anyone. */}
                  <span className="absolute right-3 top-3 rounded-pill bg-white/95 px-2.5 py-1 text-[11px] font-semibold text-green-700">
                    {p.status === "BOARDING" ? "Boarding now"
                      : p.status === "OWNED" ? "Yours" : "Adopted"}
                  </span>
                </div>
                <div className="flex flex-1 flex-col p-5">
                  <div className="flex items-start justify-between gap-3">
                    <h3 className="text-lg font-semibold leading-tight">{p.name}</h3>
                    <span className="rounded-pill bg-pw-input px-2.5 py-1 text-xs text-pw-button">
                      {p.breed}
                    </span>
                  </div>
                  <div className="mt-auto pt-5">
                    <Button variant="subtle" size="sm" full onClick={() => setQuizFor(p)}>
                      <FiCalendar className="h-4 w-4" /> Request Boarding
                    </Button>
                  </div>
                </div>
              </Card>
            ))}
          </div>
        )}
      </section>

      <section className="mt-10">
        <h2 className="text-lg font-semibold">Boarding History &amp; Requests</h2>

        {!loading && stays.length === 0 ? (
          <Card className="mt-5 px-6 py-14 text-center">
            <p className="text-[15px] font-medium text-pw-ink">No stays yet.</p>
            <p className="mt-1.5 text-sm text-pw-muted">
              Requests you send appear here, with whatever the shelter says next.
            </p>
          </Card>
        ) : (
          <Card className="mt-5 overflow-hidden">
            <ul className="divide-y divide-pw-stroke/30">
              {stays.map((b) => (
                <li key={b.id} className="flex flex-wrap items-center gap-4 px-5 py-4">
                  <span className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full bg-pw-input text-sm font-semibold text-pw-button">
                    {b.petCoverPhotoPath
                      ? <img src={`${API_BASE}/files/${b.petCoverPhotoPath}`} alt=""
                          className="h-full w-full object-cover" />
                      : b.petName.slice(0, 2)}
                  </span>
                  <div className="min-w-[190px] flex-1">
                    <p className="text-[15px] font-medium text-pw-ink">{b.petName}</p>
                    <p className="mt-0.5 flex items-center gap-1.5 text-sm text-pw-muted">
                      <FiCalendar className="h-3.5 w-3.5" />
                      {day(b.startDate)} – {day(b.endDate)} · {b.nights} night{b.nights === 1 ? "" : "s"}
                    </p>
                  </div>
                  <div className="text-right">
                    <span className={`rounded-pill px-3 py-1 text-[11px] font-semibold ${STATUS_TONE[b.status]}`}>
                      {b.status}
                    </span>
                    <p className="mt-1 text-xs text-pw-muted">
                      {b.shelterName}
                      {b.suiteName && ` · ${b.suiteName}`}
                    </p>
                  </div>
                  <Button as={Link} to={`/app/boarding/${b.id}`} variant="outline" size="sm">
                    View details
                  </Button>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </section>

      <BoardingQuizModal
        open={quizFor != null}
        onClose={() => setQuizFor(null)}
        onComplete={(attempt) => {
          setFormFor({ pet: quizFor, attemptId: attempt?.id ?? null });
          setQuizFor(null);
        }}
      />

      <BoardingRequestModal
        open={formFor != null}
        pet={formFor?.pet}
        attemptId={formFor?.attemptId}
        onClose={() => setFormFor(null)}
        onDone={(row) => {
          setFormFor(null);
          setDone(row);
          // Reload rather than pushing the returned row onto the list: the
          // server is the only thing that knows the frozen rate and the
          // estimate it worked out.
          load();
        }}
      />

      <SuccessModal
        open={done != null}
        onClose={() => setDone(null)}
        title="Boarding request sent"
        message={`${done?.shelterName ?? "The shelter"} has ${done?.petName ?? "your pet"}'s `
          + `care details and will confirm the dates shortly.`}
        actionLabel="Okay"
      />
    </AdopterLayout>
  );
}
