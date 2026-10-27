import { useCallback, useEffect, useState } from "react";
import {
  FiClock, FiRefreshCw, FiUsers, FiLogIn, FiLogOut, FiHome,
  FiAlertTriangle, FiCheckCircle, FiSunrise, FiCalendar,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Pagination from "../../components/ui/Pagination";
import { usePaged } from "../../lib/usePaged";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import SectionTabs from "../../components/portal/SectionTabs";
import { STAFF_BOARDING_TABS } from "../../lib/navTabs";
import { Select } from "../../components/ui/Field";

/**
 * Daycare, from the shelter's side. READ-ONLY, and deliberately so.
 *
 * STAFF DO NOT SIGN PEOPLE UP HERE. Every check that makes a subscription safe
 * -- the pet belongs to the caller, the quiz belongs to the caller, the quiz
 * was passed -- is a check about the CALLER. A staff-side create would have to
 * take an owner id and skip all three, which is a hole shaped exactly like
 * "sign up a pet that isn't theirs against a quiz they never took". The
 * backend has no such endpoint either.
 *
 * WHAT STAFF ACTUALLY NEED is the two things at the top. The band tiles say
 * how many places each band has SOLD -- counted from daycare_subscriptions,
 * never stored, because a maintained counter is the one that drifts. Due Today
 * says who that actually means this morning and which of them have walked in.
 *
 * THE TWO NUMBERS ARE NOT THE SAME NUMBER, and the page is laid out so they
 * cannot be mistaken for each other. A band tile reading "6 / 8" means six pets
 * are ENTITLED to come today; it does not move when one of them is dropped
 * off. Every place is held from the moment the owner subscribes, which is what
 * makes the promise worth anything -- a pet that is running late has not lost
 * its place to somebody who arrived first.
 *
 * ROOMS ARE THE OTHER STORY, and they are decided on the day: a suite is
 * picked at check-in from whatever is free, never reserved in advance. That is
 * safe ONLY because DaycareService.requireRoomFor refuses a subscription the
 * building could not house -- so there is always a room left for the last
 * arrival. `housing` is that guarantee made visible; when a band sells more
 * than the rooms hold, the tile says so in red.
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

const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

const money = (n) => `৳${Number(n ?? 0).toLocaleString()}`;

export default function StaffDaycarePage() {
  const [slots, setSlots] = useState([]);
  const [subs, setSubs] = useState([]);
  const [rooms, setRooms] = useState([]);
  // Today: who is due, who is here, and what the rooms hold.
  const [due, setDue] = useState([]);
  // V19. ATTENDANCE HISTORY. GET /staff/daycare/attendance?date= has taken a
  // date since V15 and nothing ever called it -- staff could see who was in
  // TODAY and had no way to look at any previous day.
  const [histDate, setHistDate] = useState(() => new Date().toISOString().slice(0, 10));
  // ROWS CARRY THE DATE THEY ARE FOR, so "loading" is derived rather than
  // stored. A `setHistLoading(true)` in the effect body is a synchronous
  // setState during render, which react-hooks/set-state-in-effect refuses to
  // build -- and rightly: it paints the old day's rows, then a spinner, then
  // the new day. Comparing the fetched date to the wanted one says "stale" in
  // one expression and needs no second piece of state to keep in step.
  const [history, setHistory] = useState({ date: null, rows: [] });
  const [housing, setHousing] = useState(0);
  // Which subscription's room picker is open, and what it has chosen.
  const [checkingIn, setCheckingIn] = useState(null);
  const [roomId, setRoomId] = useState("");
  const [actionError, setActionError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [todayRes, subRes, roomRes] = await Promise.all([
        api.get("/staff/daycare/today"),
        api.get("/staff/daycare/subscriptions"),
        api.get("/shelters/mine/rooms"),
      ]);
      setSlots(todayRes.data.slots);
      setDue(todayRes.data.due);
      setHousing(todayRes.data.housingPlaces);
      setSubs(subRes.data);
      setRooms(roomRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load daycare."));
    } finally {
      setLoading(false);
    }
  }, []);

  async function run(fn) {
    setBusy(true);
    setActionError("");
    try {
      await fn();
      await load();
      return true;
    } catch (err) {
      setActionError(apiError(err, "That didn't work."));
      return false;
    } finally {
      setBusy(false);
    }
  }

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
        const [todayRes, subRes, roomRes] = await Promise.all([
          // /today carries the band tiles as well, so /occupancy is no longer
          // fetched here -- it stays on the server for the adopter signup page.
          api.get("/staff/daycare/today"),
          api.get("/staff/daycare/subscriptions"),
          api.get("/shelters/mine/rooms"),
        ]);
        if (cancelled) return;
        setSlots(todayRes.data.slots);
        setDue(todayRes.data.due);
        setHousing(todayRes.data.housingPlaces);
        setSubs(subRes.data);
        setRooms(roomRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load daycare."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // TWO PAGERS, BECAUSE THEY ARE TWO LISTS WITH DIFFERENT JOBS. Due Today is
  // this morning's work and wants to be mostly visible; Subscriptions is the
  // shelter's whole book and only grows.
  // Its own effect, keyed on the date: the rest of the page does not reload
  // when somebody pages back through the week.
  useEffect(() => {
    let cancelled = false;
    api.get(`/staff/daycare/attendance?date=${histDate}`)
      .then(({ data }) => { if (!cancelled) setHistory({ date: histDate, rows: data }); })
      // An empty day and a failed fetch look the same on screen, and for this
      // panel that is acceptable: the board above it is the one staff act on.
      .catch(() => { if (!cancelled) setHistory({ date: histDate, rows: [] }); });
    return () => { cancelled = true; };
  }, [histDate]);

  const histRows = history.date === histDate ? history.rows : [];
  const histLoading = history.date !== histDate;

  const duePager = usePaged(due, 8);
  const histPager = usePaged(histRows, 10, histDate);
  const subPager = usePaged(subs, 10);

  return (
    <StaffLayout
      title="Daycare"
      subtitle="Who is booked into which band, and how much room is left."
      actions={
        <Button variant="outline" size="sm" onClick={load} disabled={loading}>
          <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
        </Button>
      }
    >
      <SectionTabs tabs={STAFF_BOARDING_TABS} />

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

      {/* ------------------------------------------------------- occupancy */}
      <div className="mb-8 grid gap-4 sm:grid-cols-3">
        {slots.map((s) => {
          const pct = s.capacity > 0
            ? Math.min(100, Math.round((s.taken / s.capacity) * 100)) : 100;
          const full = s.free <= 0;
          return (
            <Card key={s.slot} className="p-5">
              <span className={`inline-flex items-center gap-1.5 rounded-pill px-3 py-1 text-xs font-medium ${SLOT_TONE[s.slot]}`}>
                <FiClock className="h-3.5 w-3.5" /> {s.label}
              </span>
              <p className="mt-4 text-3xl font-semibold text-pw-ink">
                {s.taken}
                <span className="ml-1 text-base font-normal text-pw-muted">/ {s.capacity}</span>
              </p>
              <div className="mt-3 h-1.5 w-full overflow-hidden rounded-pill bg-pw-box">
                <div className={`h-full rounded-pill ${full ? "bg-pw-danger" : "bg-pw-button"}`}
                     style={{ width: `${pct}%` }} />
              </div>
              <p className="mt-2 text-sm text-pw-muted">
                {full ? "Sold out" : `${s.free} place${s.free === 1 ? "" : "s"} left`}
              </p>
              {/* SOLD, NOT PRESENT. This number counts subscriptions -- pets
                  entitled to come -- which is why it does not move when
                  somebody is dropped off. The board below is who is actually
                  here. */}
              {s.capacity > housing && (
                <p className="mt-2 flex items-start gap-1.5 text-xs text-pw-danger">
                  <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  Sells {s.capacity} but the rooms hold {housing}.
                </p>
              )}
            </Card>
          );
        })}
      </div>

      {/* ---------------------------------------------------- arrivals board */}
      {/* WHAT A STAFF MEMBER ACTUALLY ASKS AT HALF PAST NINE. A subscription
          says the pet comes; only an attendance row says it is here, and
          "expected and not here" is the ABSENCE of that row -- so this list
          can only come from the subscriptions, left-joined to today. */}
      <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold text-pw-ink">
        <FiSunrise className="h-5 w-5 text-pw-button" /> Due Today
      </h2>
      <p className="mb-4 text-sm text-pw-muted">
        {due.length === 0
          ? "Nobody is expected today."
          : `${due.filter((r) => r.arrived).length} of ${due.length} here`
            + `${due.some((r) => r.goneHome)
                ? ` · ${due.filter((r) => r.goneHome).length} gone home` : ""}`
            + ` · rooms hold ${housing}`}
      </p>

      {due.length > 0 && (
        <Card className="mb-8 overflow-hidden">
          <ul className="divide-y divide-pw-stroke/40">
            {duePager.shown.map((r) => (
              <li key={r.subscriptionId}
                  className="flex flex-wrap items-center gap-3 px-5 py-3.5">
                <span className={`shrink-0 rounded-pill px-2.5 py-1 text-xs font-medium ${SLOT_TONE[r.slot]}`}>
                  {r.slotLabel}
                </span>

                <div className="min-w-0 flex-1">
                  <p className="text-[15px] font-medium text-pw-ink">{r.petName}</p>
                  <p className="mt-0.5 text-sm text-pw-muted">
                    {r.ownerName}
                    {r.arrived && r.suiteName && <> &middot; {r.suiteName}</>}
                  </p>
                </div>

                {/* THREE STATES, NOT TWO. A pet collected at noon is not the
                    same as one that never came, and showing both as "not
                    here" would have staff ringing an owner whose dog is
                    already home. */}
                {r.goneHome ? (
                  <span className="inline-flex items-center gap-1.5 rounded-pill bg-pw-box px-2.5 py-1 text-xs font-medium text-pw-muted">
                    <FiCheckCircle className="h-3.5 w-3.5" /> Gone home
                  </span>
                ) : r.arrived ? (
                  <div className="flex items-center gap-2">
                    <span className="inline-flex items-center gap-1.5 rounded-pill bg-green-50 px-2.5 py-1 text-xs font-medium text-green-700">
                      <FiHome className="h-3.5 w-3.5" /> Here
                    </span>
                    <Button size="sm" variant="outline" disabled={busy}
                      onClick={() => run(() =>
                        api.post(`/staff/daycare/attendance/${r.visitId}/check-out`))}>
                      <FiLogOut className="h-3.5 w-3.5" /> Out
                    </Button>
                  </div>
                ) : checkingIn === r.subscriptionId ? (
                  <div className="flex items-center gap-2">
                    {/* Species-filtered here as well as on the server: a dog
                        run offered for a cat is an option that exists only to
                        be refused. */}
                    <Select className="w-44" value={roomId}
                      onChange={(e) => setRoomId(e.target.value)}>
                      <option value="">Room…</option>
                      {rooms
                        .filter((x) => x.active
                          && x.occupied < x.capacity
                          && (x.species == null || x.species === r.petSpecies))
                        .map((x) => (
                          <option key={x.id} value={x.id}>
                            {x.name} — {x.occupied}/{x.capacity}
                          </option>
                        ))}
                    </Select>
                    <Button size="sm" disabled={busy || !roomId}
                      onClick={async () => {
                        const ok = await run(() => api.post("/staff/daycare/attendance", {
                          subscriptionId: r.subscriptionId, suiteId: Number(roomId),
                        }));
                        if (ok) { setCheckingIn(null); setRoomId(""); }
                      }}>
                      Confirm
                    </Button>
                    <button type="button" className="text-xs text-pw-muted hover:text-pw-body"
                      onClick={() => { setCheckingIn(null); setRoomId(""); }}>
                      Cancel
                    </button>
                  </div>
                ) : (
                  <div className="flex items-center gap-2">
                    <span className="inline-flex items-center gap-1.5 rounded-pill bg-amber-50 px-2.5 py-1 text-xs font-medium text-amber-700">
                      <FiClock className="h-3.5 w-3.5" /> Waiting
                    </span>
                    <Button size="sm" disabled={busy}
                      onClick={() => { setCheckingIn(r.subscriptionId); setRoomId(""); }}>
                      <FiLogIn className="h-3.5 w-3.5" /> Check in
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
          <Pagination {...duePager.props} noun="expected today" />
        </Card>
      )}

      {/* ------------------------------------------------------- attendance */}
      {/* WHO WAS HERE, ON ANY DAY. The endpoint has taken a date since V15 and
          nothing called it, so "who came last Tuesday" was unanswerable.
          Reads v_daycare_day, the view V19 added so the live board and this
          one cannot disagree about what a visit is. */}
      <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold text-pw-ink">
        <FiCalendar className="h-5 w-5 text-pw-button" /> Attendance
      </h2>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <input
          type="date"
          value={histDate}
          // NO max. A shelter looking at tomorrow sees an empty list, which is
          // the true answer -- nobody has been checked in yet.
          onChange={(e) => setHistDate(e.target.value)}
          className="h-10 rounded-card border border-pw-stroke/60 bg-pw-input px-4 text-sm text-pw-ink focus:border-pw-button focus:outline-none"
          aria-label="Attendance date"
        />
        <button type="button"
          className="text-sm font-medium text-pw-button hover:underline"
          onClick={() => setHistDate(new Date().toISOString().slice(0, 10))}>
          Today
        </button>
        <span className="text-sm text-pw-muted">
          {histLoading ? "Loading…"
            : histRows.length === 0 ? "Nobody was checked in."
            : `${histRows.length} visit${histRows.length === 1 ? "" : "s"}`
              + ` · ${histRows.filter((v) => !v.checkedOutAt).length} still here`}
        </span>
      </div>

      {histRows.length > 0 && (
        <Card className="mb-8 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="bg-pw-box text-left text-xs uppercase tracking-wide text-pw-body">
                <tr>
                  <th className="px-5 py-3 font-medium">Pet</th>
                  <th className="px-5 py-3 font-medium">Band</th>
                  <th className="px-5 py-3 font-medium">Room</th>
                  <th className="px-5 py-3 font-medium">In</th>
                  <th className="px-5 py-3 font-medium">Out</th>
                  <th className="px-5 py-3 text-right font-medium">On site</th>
                </tr>
              </thead>
              <tbody>
                {histPager.shown.map((v) => (
                  <tr key={v.id} className="border-t border-pw-stroke/40">
                    <td className="px-5 py-3 font-medium text-pw-ink">
                      {v.petName}
                      <span className="block text-xs text-pw-muted">{v.ownerName}</span>
                    </td>
                    <td className="px-5 py-3">
                      <span className={`rounded-pill px-2.5 py-1 text-xs font-medium ${SLOT_TONE[v.slot]}`}>
                        {v.slotLabel}
                      </span>
                    </td>
                    <td className="px-5 py-3 text-pw-body">{v.suiteName}</td>
                    <td className="px-5 py-3 text-pw-body tabular-nums">
                      {String(v.checkedInAt).slice(11, 16)}
                      <span className="block text-xs text-pw-muted">{v.checkedInByName}</span>
                    </td>
                    <td className="px-5 py-3 text-pw-body tabular-nums">
                      {v.checkedOutAt ? (
                        <>
                          {String(v.checkedOutAt).slice(11, 16)}
                          <span className="block text-xs text-pw-muted">{v.checkedOutByName}</span>
                        </>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 rounded-pill bg-green-50 px-2.5 py-1 text-xs font-medium text-green-700">
                          <FiHome className="h-3.5 w-3.5" /> here
                        </span>
                      )}
                    </td>
                    {/* MINUTES COME FROM THE VIEW, not from subtracting two
                        timestamps here. For a pet still on site the view
                        measures against NOW on the server clock, which is the
                        one both ends already agree on. */}
                    <td className="px-5 py-3 text-right tabular-nums text-pw-body">
                      {Math.floor(v.minutesOnSite / 60)}h {v.minutesOnSite % 60}m
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination {...histPager.props} noun="visits" />
        </Card>
      )}

      {/* --------------------------------------------------- subscriptions */}
      <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-pw-ink">
        <FiUsers className="h-5 w-5 text-pw-button" /> Subscriptions
      </h2>

      {!loading && subs.length === 0 && (
        <Card className="px-6 py-10 text-center">
          <p className="text-pw-body">Nobody has subscribed yet.</p>
          <p className="mt-1 text-sm text-pw-muted">
            Owners sign up themselves, after passing the boarding quiz. Set the
            price and the per-band capacity on the My Shelter page.
          </p>
        </Card>
      )}

      {subs.length > 0 && (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-pw-box text-left text-xs uppercase tracking-wide text-pw-body">
                <tr>
                  <th className="px-5 py-3 font-medium">Pet</th>
                  <th className="px-5 py-3 font-medium">Owner</th>
                  <th className="px-5 py-3 font-medium">Band</th>
                  <th className="px-5 py-3 font-medium">Since</th>
                  <th className="px-5 py-3 text-right font-medium">Per month</th>
                  <th className="px-5 py-3 text-right font-medium">Bills</th>
                  <th className="px-5 py-3 font-medium">Status</th>
                  <th className="px-5 py-3 font-medium">Today</th>
                </tr>
              </thead>
              <tbody>
                {subPager.shown.map((s) => (
                  <tr key={s.id} className="border-t border-pw-stroke/40">
                    <td className="px-5 py-3 font-medium text-pw-ink">{s.petName}</td>
                    <td className="px-5 py-3 text-pw-body">
                      {s.ownerName}
                      <span className="block text-xs text-pw-muted">{s.ownerEmail}</span>
                    </td>
                    <td className="px-5 py-3">
                      <span className={`rounded-pill px-2.5 py-1 text-xs font-medium ${SLOT_TONE[s.slot]}`}>
                        {s.slotLabel}
                      </span>
                    </td>
                    <td className="px-5 py-3 text-pw-body">
                      {day(s.startDate)}
                      {s.endDate && <span className="block text-xs text-pw-muted">to {day(s.endDate)}</span>}
                    </td>
                    <td className="px-5 py-3 text-right text-pw-body">{money(s.monthlyPrice)}</td>
                    <td className="px-5 py-3 text-right text-pw-body">{s.slipsIssued}</td>
                    <td className="px-5 py-3">
                      <span className={`rounded-pill px-2.5 py-1 text-xs font-medium ${STATUS_TONE[s.status]}`}>
                        {s.status.toLowerCase()}
                      </span>
                    </td>

                    {/* READ-ONLY, because Due Today above is where check-in
                        happens. This column used to carry its own room picker
                        keyed on `checkingIn === s.id` -- the same state the
                        board uses -- so opening one opened both. One place to
                        do the thing, one place to read it. */}
                    <td className="px-5 py-3">
                      {s.status !== "ACTIVE" ? (
                        <span className="text-xs text-pw-muted">—</span>
                      ) : s.hereToday ? (
                        <span className="inline-flex items-center gap-1.5 rounded-pill bg-green-50 px-2.5 py-1 text-xs font-medium text-green-700">
                          <FiHome className="h-3.5 w-3.5" /> {s.todaySuiteName}
                        </span>
                      ) : (
                        <span className="text-xs text-pw-muted">not in</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination {...subPager.props} noun="subscriptions" />
        </Card>
      )}
    </StaffLayout>
  );
}
