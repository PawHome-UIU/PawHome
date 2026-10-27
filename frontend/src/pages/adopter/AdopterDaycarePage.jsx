import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { FiClock, FiRefreshCw, FiMapPin, FiFileText, FiXCircle, FiHome } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import SuccessModal from "../../components/ui/SuccessModal";
import BoardingQuizModal from "../../components/adopter/BoardingQuizModal";
import DaycareSubscribeModal from "../../components/adopter/DaycareSubscribeModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import SectionTabs from "../../components/portal/SectionTabs";
import { ADOPTER_BOARDING_TABS } from "../../lib/navTabs";

/**
 * Daycare, from the owner's side.
 *
 * WHAT THIS SELLS. One fixed band a day, every day, billed monthly -- not a
 * stay. The three bands are the same at every shelter, so what varies between
 * the cards below is the price and how full each band is.
 *
 * THE QUIZ IS A HARD GATE and it is the SAME boarding quiz. That is not
 * laziness: a subscription leaves the animal at a shelter every weekday
 * indefinitely, so the questions about temperament, vaccination and emergency
 * vet authority matter more than they do for one week away, not less. The
 * server requires a PASSED BOARDING attempt and fk_sub_attempt makes it
 * impossible to attach any other kind.
 *
 * OCCUPANCY IS LIVE, NOT CACHED. A full slot is disabled here, but that is
 * only the polite half -- the server takes a pessimistic lock and re-counts
 * before it writes, because two people clicking the last place in the same
 * second would both see "1 left" on a page rendered a minute ago.
 */
const SLOT_TONE = {
  MORNING: "bg-amber-50 text-amber-700",
  MIDDAY: "bg-sky-50 text-sky-700",
  AFTERNOON: "bg-pw-input text-pw-button",
};

const STATUS_TONE = {
  ACTIVE: "bg-green-50 text-green-700",
  CANCELLED: "bg-pw-input text-pw-muted",
  EXPIRED: "bg-pw-box text-pw-body",
};

// A LocalDate is "2026-09-04" -- no time and no zone. new Date() on that
// parses it as UTC midnight, which anywhere behind UTC renders as the day
// before.
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

const money = (n) => `৳${Number(n ?? 0).toLocaleString()}`;

export default function AdopterDaycarePage() {
  const [offers, setOffers] = useState([]);
  const [mine, setMine] = useState([]);
  const [pets, setPets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [actionError, setActionError] = useState("");

  // The signup flow as two nullable slots rather than a step counter: the
  // chosen shelter and slot are carried the whole way, so neither modal can
  // open without knowing what is being subscribed to.
  const [quizFor, setQuizFor] = useState(null);     // { shelter, slot }
  const [formFor, setFormFor] = useState(null);     // { shelter, slot, attemptId }
  const [done, setDone] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [offerRes, mineRes, petRes] = await Promise.all([
        api.get("/daycare/offers"),
        api.get("/daycare/subscriptions/mine"),
        api.get("/my-pets"),
      ]);
      setOffers(offerRes.data);
      setMine(mineRes.data);
      setPets(petRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load daycare."));
    } finally {
      setLoading(false);
    }
  }, []);

  // NOT `useEffect(() => { load(); })`. load() calls setLoading(true)
  // synchronously, and a setState in an effect body triggers a cascading
  // render -- react-hooks/set-state-in-effect refuses to build it. The first
  // fetch is inlined instead, with a cancelled guard so a fast unmount does
  // not set state on a gone component; `loading` already starts true, so
  // nothing needs to be set before the await. load() stays for Refresh.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [offerRes, mineRes, petRes] = await Promise.all([
          api.get("/daycare/offers"),
          api.get("/daycare/subscriptions/mine"),
          api.get("/my-pets"),
        ]);
        if (cancelled) return;
        setOffers(offerRes.data);
        setMine(mineRes.data);
        setPets(petRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load daycare."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const cancel = async (id) => {
    setActionError("");
    try {
      await api.post(`/daycare/subscriptions/${id}/cancel`);
      await load();
    } catch (err) {
      setActionError(apiError(err, "Could not cancel that subscription."));
    }
  };

  const active = mine.filter((s) => s.status === "ACTIVE");
  const past = mine.filter((s) => s.status !== "ACTIVE");

  return (
    <AdopterLayout
      title="Daycare"
      subtitle="A few hours every day, in the same safe place."
      actions={
        <Button variant="outline" size="sm" onClick={load} disabled={loading}>
          <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
        </Button>
      }
    >
      <SectionTabs tabs={ADOPTER_BOARDING_TABS} />

      {loadError && (
        <p role="alert" className="mb-6 rounded-card bg-rose-50 px-5 py-4 text-sm text-pw-danger">
          {loadError}
        </p>
      )}
      {actionError && (
        <p role="alert" className="mb-6 rounded-card bg-rose-50 px-5 py-4 text-sm text-pw-danger">
          {actionError}
        </p>
      )}

      {/* ---------------------------------------------- my subscriptions */}
      <section className="mb-10">
        <div className="mb-4 flex items-baseline justify-between gap-4">
          <h2 className="text-lg font-semibold text-pw-ink">My subscriptions</h2>
          <Link to="/app/slips" className="text-sm font-medium text-pw-button hover:underline">
            View my bills
          </Link>
        </div>

        {!loading && mine.length === 0 && (
          <Card className="px-6 py-8 text-center">
            <p className="text-pw-body">
              No subscriptions yet. Pick a shelter and a time below.
            </p>
          </Card>
        )}

        <div className="grid gap-4 md:grid-cols-2">
          {[...active, ...past].map((s) => (
            <Card key={s.id} className="min-w-0 p-5">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h3 className="text-base font-semibold text-pw-ink">{s.petName}</h3>
                  <p className="mt-0.5 flex items-center gap-1.5 text-sm text-pw-muted">
                    <FiMapPin className="h-3.5 w-3.5" /> {s.shelterName}
                  </p>
                </div>
                <span className={`rounded-pill px-3 py-1 text-xs font-medium ${STATUS_TONE[s.status]}`}>
                  {s.status.toLowerCase()}
                </span>
              </div>

              <div className="mt-4 flex flex-wrap items-center gap-2">
                <span className={`inline-flex items-center gap-1.5 rounded-pill px-3 py-1 text-xs font-medium ${SLOT_TONE[s.slot]}`}>
                  <FiClock className="h-3.5 w-3.5" /> {s.slotLabel}
                </span>
                <span className="rounded-pill bg-pw-box px-3 py-1 text-xs font-medium text-pw-ink">
                  {money(s.monthlyPrice)} / month
                </span>
                {/* WHERE THE PET IS, RIGHT NOW. SubscriptionRow has carried
                    hereToday / todaySuiteName since the attendance work and
                    the owner was never shown either -- the data was already on
                    the wire and only the staff page read it. The one question
                    somebody with a dog at daycare actually has. */}
                {s.hereToday && (
                  <span className="inline-flex items-center gap-1.5 rounded-pill bg-green-50 px-3 py-1 text-xs font-medium text-green-700">
                    <FiHome className="h-3.5 w-3.5" />
                    At the shelter now{s.todaySuiteName ? ` · ${s.todaySuiteName}` : ""}
                  </span>
                )}
                {s.slipsIssued > 0 && (
                  <span className="inline-flex items-center gap-1.5 rounded-pill bg-pw-box px-3 py-1 text-xs font-medium text-pw-body">
                    <FiFileText className="h-3.5 w-3.5" />
                    {s.slipsIssued} bill{s.slipsIssued === 1 ? "" : "s"}
                  </span>
                )}
              </div>

              <p className="mt-3 text-sm text-pw-body">
                From {day(s.startDate)}
                {s.endDate && <> &middot; ended {day(s.endDate)}</>}
              </p>

              {s.status === "ACTIVE" && (
                <div className="mt-4 flex justify-end">
                  <Button variant="outline" size="sm" onClick={() => cancel(s.id)}>
                    <FiXCircle className="h-4 w-4" /> Cancel
                  </Button>
                </div>
              )}
            </Card>
          ))}
        </div>
      </section>

      {/* ------------------------------------------------- where to sign up */}
      <section>
        <h2 className="mb-1 text-lg font-semibold text-pw-ink">Where you can subscribe</h2>
        <p className="mb-4 text-sm text-pw-muted">
          Three bands a day. Pick the one that fits your morning.
        </p>

        {!loading && offers.length === 0 && (
          <Card className="px-6 py-8 text-center">
            <p className="text-pw-body">No shelter is running daycare just yet.</p>
          </Card>
        )}

        <div className="grid gap-5 lg:grid-cols-2">
          {offers.map((o) => (
            <Card key={o.id} className="min-w-0 p-6">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h3 className="text-base font-semibold text-pw-ink">{o.name}</h3>
                  <p className="mt-0.5 text-sm text-pw-muted">{o.city}, {o.state}</p>
                </div>
                <span className="text-lg font-semibold text-pw-ink">
                  {money(o.monthlyPrice)}
                  <span className="ml-1 text-sm font-normal text-pw-muted">/ month</span>
                </span>
              </div>

              <ul className="mt-5 space-y-2.5">
                {o.slots.map((s) => {
                  const full = s.free <= 0;
                  const pct = s.capacity > 0
                    ? Math.min(100, Math.round((s.taken / s.capacity) * 100)) : 100;
                  return (
                    <li key={s.slot} className="flex items-center gap-4">
                      <div className="min-w-0 flex-1">
                        <div className="flex items-baseline justify-between gap-3">
                          <span className="text-sm font-medium text-pw-ink">{s.label}</span>
                          {/* Always the count, never the word "full": the
                              button already says that, and "0 of 20 left"
                              tells you how big the band is as well. */}
                          <span className="text-xs text-pw-muted">
                            {s.free} of {s.capacity} left
                          </span>
                        </div>
                        {/* The bar is the only honest way to show "nearly
                            full"; a number alone reads the same at 19/20 and
                            at 2/20. */}
                        <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-pill bg-pw-box">
                          <div
                            className={`h-full rounded-pill ${full ? "bg-pw-muted" : "bg-pw-button"}`}
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                      </div>
                      <Button
                        size="sm"
                        variant={full ? "subtle" : "outline"}
                        disabled={full || pets.length === 0}
                        onClick={() => setQuizFor({
                          shelter: { id: o.id, name: o.name, monthlyPrice: o.monthlyPrice },
                          slot: s,
                        })}
                      >
                        {full ? "Full" : "Subscribe"}
                      </Button>
                    </li>
                  );
                })}
              </ul>

              {pets.length === 0 && (
                <p className="mt-4 text-sm text-pw-muted">
                  Add one of your own pets first — daycare is for pets you own.
                </p>
              )}
            </Card>
          ))}
        </div>
      </section>

      {/* The quiz, then the form. The attempt id is handed from one to the
          other and never touched in between. */}
      <BoardingQuizModal
        open={!!quizFor}
        onClose={() => setQuizFor(null)}
        onComplete={(attempt) => {
          const ctx = quizFor;
          setQuizFor(null);
          // The server requires a PASS and will refuse otherwise. Saying so
          // here means the customer is told why instead of being walked
          // through a form that is going to be rejected at the end of it --
          // and silently doing nothing would be worse than either.
          if (!attempt?.passed) {
            setActionError(
              "That boarding quiz was not passed, so a daycare subscription "
              + "cannot be started yet. You can take it again.",
            );
            return;
          }
          setActionError("");
          setFormFor({ ...ctx, attemptId: attempt.id });
        }}
      />

      <DaycareSubscribeModal
        open={!!formFor}
        onClose={() => setFormFor(null)}
        shelter={formFor?.shelter}
        slot={formFor?.slot}
        attemptId={formFor?.attemptId}
        pets={pets}
        onDone={(row) => {
          setFormFor(null);
          setDone(row);
          load();
        }}
      />

      <SuccessModal
        open={!!done}
        onClose={() => setDone(null)}
        title="Subscription started"
        message={done
          ? `${done.petName} has the ${done.slotLabel} slot at ${done.shelterName}. `
            + `Your first bill will be issued on the 1st.`
          : ""}
      />
    </AdopterLayout>
  );
}
