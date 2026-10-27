import { useEffect, useState } from "react";
import {
  FiHome, FiUsers, FiHeart, FiGrid, FiMail, FiPhone, FiMapPin,
  FiPlus, FiEdit2, FiSlash, FiCheck, FiInfo, FiClock,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import PanelCard from "../../components/portal/PanelCard";
import StatTile from "../../components/admin/StatTile";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import Modal from "../../components/ui/Modal";
import { Select } from "../../components/ui/Field";
import Pagination from "../../components/ui/Pagination";
import { usePaged } from "../../lib/usePaged";
import SuiteModal from "../../components/shelter/SuiteModal";
import { speciesLabel } from "../../lib/species";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * The staff member's own shelter.
 *
 * WHY THIS PAGE EXISTS. GET /shelters/mine has been on the server since the
 * shelter increment and nothing called it, so a staff member could not read
 * their own address, capacity, nightly boarding rate or room list -- only an
 * admin could, from a different portal. That is a strange hole for the one
 * person who answers the phone when a customer asks any of those questions.
 *
 * READ-ONLY ABOVE, EDITABLE BELOW, AND THE SPLIT IS DELIBERATE. Name, address,
 * capacity and the nightly rate are the network's record of this shelter:
 * changing them is an admin decision and the server would refuse the request
 * anyway. Suites are the opposite -- they are the shelter's own furniture, the
 * boarding accept dialog picks from them, and until now building a kennel
 * meant asking an admin to type it in. So the rooms are managed here and
 * everything above them says, in one line, who to ask.
 */

// SPECIES and speciesLabel moved to components/shelter/SuiteModal with the
// dialog itself, so the staff and admin portals share one definition.

const STATUS_TONE = {
  ACTIVE: "bg-green-50 text-green-700",
  PENDING: "bg-amber-50 text-amber-700",
  INACTIVE: "bg-rose-50 text-rose-700",
};

/**
 * Who is on the premises, and how long they have been here.
 *
 * THREE KINDS IN ONE LIST because that is the question: not "which boarders",
 * but "who is here". The kind comes from which arm of a UNION the row is from
 * -- no column anywhere stores it.
 *
 * SORTED LONGEST FIRST, which is the only reason anybody opens this: the
 * animal that has been waiting longest is the one that needs something doing
 * about it.
 */
const KIND_TONE = {
  RESIDENT: "bg-pw-input text-pw-button",
  BOARDING: "bg-sky-50 text-sky-700",
  DAYCARE: "bg-amber-50 text-amber-700",
};

const stayLabel = (days, kind) => {
  if (kind === "DAYCARE") return "here today";
  if (days <= 0) return "arrived today";
  if (days === 1) return "1 day";
  if (days < 31) return `${days} days`;
  const months = Math.floor(days / 30);
  return `${months} month${months === 1 ? "" : "s"}`;
};

export default function ShelterPage() {
  const [shelter, setShelter] = useState(null);
  const [suites, setSuites] = useState([]);
  // WHO IS IN EACH ROOM, from a different endpoint than the rooms themselves.
  // /mine/suites describes the rooms; /mine/rooms describes the building at
  // this moment. Keeping them apart means renaming a room does not have to
  // compute an occupant.
  const [rooms, setRooms] = useState([]);
  const [onSite, setOnSite] = useState([]);
  const [loadError, setLoadError] = useState("");
  const [suiteOpen, setSuiteOpen] = useState(false);
  const [editSuite, setEditSuite] = useState(null);
  const [retiring, setRetiring] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [mine, suiteRes, roomRes, siteRes] = await Promise.all([
          api.get("/shelters/mine"),
          api.get("/shelters/mine/suites"),
          api.get("/shelters/mine/rooms"),
          api.get("/shelters/mine/on-site"),
        ]);
        if (cancelled) return;
        setShelter(mine.data);
        setSuites(suiteRes.data);
        setRooms(roomRes.data);
        setOnSite(siteRes.data);
      } catch (err) {
        if (cancelled) return;
        setLoadError(apiError(err, "Your shelter could not be loaded."));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /** One saved room replaces its own entry; a new one joins and re-sorts. */
  function absorb(row) {
    setSuites((list) => {
      const without = list.filter((s) => s.id !== row.id);
      return [...without, row].sort((a, b) => a.name.localeCompare(b.name));
    });
  }

  async function retire() {
    setBusy(true);
    try {
      const { data } = await api.post(`/shelters/mine/suites/${retiring.id}/deactivate`);
      absorb(data);
      setRetiring(null);
    } catch (err) {
      setLoadError(apiError(err, "That room could not be retired."));
    } finally {
      setBusy(false);
    }
  }

  /** Bring a retired room back. The same endpoint that renames one does it. */
  async function restore(suite) {
    setBusy(true);
    try {
      const { data } = await api.put(`/shelters/mine/suites/${suite.id}`, {
        name: suite.name, species: suite.species, active: true,
      });
      absorb(data);
    } catch (err) {
      setLoadError(apiError(err, "That room could not be reopened."));
    } finally {
      setBusy(false);
    }
  }

  const open = suites.filter((s) => s.active);
  const retired = suites.filter((s) => !s.active);

  // TWO PAGERS. On Site Now is every animal in the building, which at a
  // hundred-place shelter is a hundred rows; the room list is open rooms then
  // retired ones, and a shelter that has been running a while has a lot of
  // retired rooms -- they are never deleted, only closed.
  const sitePager = usePaged(onSite, 10);
  const roomPager = usePaged([...open, ...retired], 10);

  const line = (label, value, Icon) => (
    <div className="flex items-start gap-3 py-3">
      <Icon className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
      <div className="min-w-0">
        <p className="text-xs font-medium uppercase tracking-wide text-pw-muted">{label}</p>
        <p className="mt-0.5 break-words text-[15px] text-pw-ink">{value || "—"}</p>
      </div>
    </div>
  );

  return (
    <StaffLayout
      title="My Shelter"
      subtitle="Your shelter's record, and the rooms you board animals in."
    >
      {loadError && (
        <Card className="mb-5 border-pw-danger/40 bg-rose-50/60 p-4">
          <p className="text-sm text-pw-danger">{loadError}</p>
        </Card>
      )}

      {shelter && (
        <>
          <Card className="overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-4 bg-pw-sidebar px-6 py-5">
              <div className="flex items-center gap-4">
                {/* No placeholder square when there is no logo -- an empty box
                    beside the name reads as a broken image, not an absence. */}
                {shelter.logoPath && (
                  <span className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-2xl bg-white">
                    <img src={`${API_BASE}/files/${shelter.logoPath}`} alt=""
                      className="h-full w-full object-contain" />
                  </span>
                )}
                <div>
                  <h2 className="text-xl font-semibold text-white">{shelter.name}</h2>
                  <p className="mt-0.5 text-sm text-white/85">{shelter.location}</p>
                </div>
              </div>
              <span className={`rounded-pill px-3 py-1 text-xs font-semibold ${
                STATUS_TONE[shelter.status] ?? STATUS_TONE.PENDING}`}>
                {shelter.status}
              </span>
            </div>

            <div className="grid gap-x-8 px-6 py-4 sm:grid-cols-2 lg:grid-cols-3">
              {line("Email", shelter.email, FiMail)}
              {line("Phone", shelter.phone, FiPhone)}
              {line("Address", [shelter.addressLine, shelter.city, shelter.state, shelter.zip]
                .filter(Boolean).join(", "), FiMapPin)}
            </div>

            {shelter.description && (
              <div className="border-t border-pw-stroke/40 px-6 py-4">
                <p className="whitespace-pre-line text-[15px] leading-relaxed text-pw-body">
                  {shelter.description}
                </p>
              </div>
            )}

            <div className="flex items-start gap-2 border-t border-pw-stroke/40 bg-pw-tint px-6 py-3">
              <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-muted" />
              <p className="text-sm text-pw-muted">
                These details are the network's record of your shelter. Ask an
                administrator to change any of them — including the nightly
                boarding rate.
              </p>
            </div>
          </Card>

          <div className="mt-5 grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            {/* TWO NUMBERS, BECAUSE THEY ARE TWO FACTS. onSiteCount is who is
                in the building; petCount is the shelter's books. Both used to
                be rendered as petCount under the label "Pets in Care", which
                is why accepting a boarding request appeared to change nothing
                -- a boarded pet belongs to a household and was never on those
                books. */}
            <StatTile icon={FiHome} value={shelter.onSiteCount} label="On Site Now" tone="good" />
            <StatTile icon={FiHeart} value={shelter.petCount} label="Our Animals" />
            <StatTile icon={FiHome} value={shelter.capacity} label="Kennel Capacity" />
            <StatTile icon={FiUsers} value={shelter.staffCount} label="Staff Members" tone="good" />
            <StatTile
              icon={FiGrid}
              value={shelter.boardingRateDaily == null
                ? "—"
                : `৳${Number(shelter.boardingRateDaily).toFixed(2)}`}
              label="Boarding, per night"
              tone="warning"
            />
            {/* V13. An em dash rather than "৳0.00": zero means this shelter
                does not run daycare, and a price of nothing reads as free. */}
            <StatTile
              icon={FiClock}
              value={Number(shelter.daycareMonthlyPrice) > 0
                ? `৳${Number(shelter.daycareMonthlyPrice).toFixed(2)}`
                : "—"}
              label="Daycare, per month"
            />
          </div>
        </>
      )}

      {/* ---------------------------------------------- who is here, and how long */}
      <PanelCard
        title={`On Site Now${onSite.length ? ` — ${onSite.length}` : ""}`}
        icon={FiHeart}
        className="mt-5"
      >
        {onSite.length === 0 ? (
          <p className="px-6 py-10 text-center text-sm text-pw-muted">
            Nobody is in the building right now.
          </p>
        ) : (
          <ul className="divide-y divide-pw-stroke/40">
            {sitePager.shown.map((a) => (
              <li key={`${a.kind}-${a.petId}-${a.since}`}
                  className="flex flex-wrap items-center gap-3 px-6 py-3.5">
                <span className={`rounded-pill px-2.5 py-1 text-xs font-medium ${KIND_TONE[a.kind]}`}>
                  {a.kind.toLowerCase()}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-[15px] font-medium text-pw-ink">{a.petName}</p>
                  <p className="mt-0.5 text-sm text-pw-muted">
                    {a.roomName ? a.roomName : "no room assigned"}
                    {a.ownerName && <> &middot; {a.ownerName}</>}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-[15px] font-medium text-pw-ink">
                    {stayLabel(a.daysHere, a.kind)}
                  </p>
                  <p className="text-xs text-pw-muted">since {a.since}</p>
                </div>
              </li>
            ))}
          </ul>
        )}
        <Pagination {...sitePager.props} noun="on site" />
      </PanelCard>

      <PanelCard
        /* PLACES, NOT ROOMS. "4 of 6 rooms free" was the right headline while a
           room held one animal; since V16 it can say "1 of 6 free" about a
           shelter with forty empty places in it, because one cat in a
           twenty-place loft closed the whole room. So the fraction counts
           places: the numerator is what the shelter can still take. */
        title={`Boarding Rooms${rooms.length
          ? ` — ${rooms.filter((r) => r.active)
                       .reduce((n, r) => n + Math.max(0, r.capacity - r.occupied), 0)}`
            + ` of ${rooms.filter((r) => r.active)
                          .reduce((n, r) => n + r.capacity, 0)} places free`
          : ""}`}
        icon={FiGrid}
        className="mt-5"
        action={
          <Button size="sm" variant="white"
            onClick={() => { setEditSuite(null); setSuiteOpen(true); }}>
            <FiPlus className="h-4 w-4" /> Add Room
          </Button>
        }
      >
        {open.length === 0 && retired.length === 0 ? (
          <p className="px-6 py-10 text-center text-sm text-pw-muted">
            No rooms yet. Add one and it appears in the boarding accept dialog.
          </p>
        ) : (
          <ul className="divide-y divide-pw-stroke/40">
            {roomPager.shown.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center gap-3 px-6 py-4">
                <div className="min-w-0 flex-1">
                  <p className={`text-[15px] font-medium ${
                    s.active ? "text-pw-ink" : "text-pw-muted line-through"}`}>
                    {s.name}
                  </p>
                  <p className="mt-0.5 text-sm text-pw-muted">
                    {speciesLabel(s.species)} · {s.capacity} place{s.capacity === 1 ? "" : "s"}
                  </p>
                  {/* HOW FULL, AND THEN WHO. Two separate facts and they are
                      shown separately on purpose: the fraction is what decides
                      whether the next animal can go in here, and the sentence
                      under it is who to picture. "Cat Loft A — occupied" is
                      equally true with one cat and with twenty, which is why
                      the state on its own stopped being an answer at V16. */}
                  {(() => {
                    const r = rooms.find((x) => x.id === s.id);
                    if (!r || !s.active) return null;
                    const full = r.occupied >= r.capacity;
                    const tone = full
                      ? "bg-rose-50 text-rose-700"
                      : r.occupied === 0
                        ? "bg-green-50 text-green-700"
                        : "bg-amber-50 text-amber-700";
                    return (
                      <>
                        <p className="mt-1.5 flex flex-wrap items-center gap-2">
                          <span className={`rounded-pill px-2.5 py-1 text-xs font-semibold tabular-nums ${tone}`}>
                            {r.occupied} / {r.capacity} pets
                          </span>
                          {full && (
                            <span className="text-xs font-medium text-pw-danger">
                              Full — no new animals can be assigned here
                            </span>
                          )}
                          {!full && r.occupied > 0 && (
                            <span className="text-xs text-pw-muted">
                              {r.capacity - r.occupied} place
                              {r.capacity - r.occupied === 1 ? "" : "s"} left
                            </span>
                          )}
                        </p>

                        {/* ONE OCCUPANT NAMED, NOT ALL OF THEM. The earliest
                            arrival gets the line; the On Site Now panel above
                            is where the full list lives, and repeating it per
                            room would be the same twenty names twice. */}
                        {r.state === "FREE" && (
                          <p className="mt-1 text-sm text-pw-ok">Empty</p>
                        )}
                        {r.state === "RESERVED" && (
                          <p className="mt-1 text-sm text-amber-700">
                            Booked for {r.petName} — {r.from} to {r.to}, not arrived
                            {r.occupied > 1 && ` · and ${r.occupied - 1} other${
                              r.occupied - 1 === 1 ? "" : "s"}`}
                          </p>
                        )}
                        {r.state === "DAYCARE" && (
                          <p className="mt-1 text-sm text-pw-button">
                            {r.petName} · daycare {r.slotLabel}
                            {r.occupied > 1 && ` · and ${r.occupied - 1} other${
                              r.occupied - 1 === 1 ? "" : "s"}`}
                          </p>
                        )}
                        {r.state === "OCCUPIED" && (
                          <p className="mt-1 text-sm text-pw-body">
                            {r.petName} ({r.ownerName}) · night {r.nightsSoFar} of a stay to {r.to}
                            {r.occupied > 1 && ` · and ${r.occupied - 1} other${
                              r.occupied - 1 === 1 ? "" : "s"}`}
                          </p>
                        )}
                      </>
                    );
                  })()}
                </div>

                {!s.active && (
                  <span className="rounded-pill bg-pw-box px-3 py-1 text-xs font-medium text-pw-muted">
                    Out of service
                  </span>
                )}

                <div className="flex gap-2">
                  <Button size="sm" variant="outline" disabled={busy}
                    onClick={() => { setEditSuite(s); setSuiteOpen(true); }}>
                    <FiEdit2 className="h-3.5 w-3.5" /> Edit
                  </Button>
                  {s.active ? (
                    <Button size="sm" variant="subtle" disabled={busy}
                      onClick={() => setRetiring(s)}>
                      <FiSlash className="h-3.5 w-3.5" /> Retire
                    </Button>
                  ) : (
                    <Button size="sm" variant="subtle" disabled={busy}
                      onClick={() => restore(s)}>
                      <FiCheck className="h-3.5 w-3.5" /> Reopen
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
        <Pagination {...roomPager.props} noun="rooms" />
      </PanelCard>

      <SuiteModal
        // Remounts per room, so the form seeds from the right one without an
        // effect copying props into state.
        key={editSuite?.id ?? "new"}
        open={suiteOpen}
        suite={editSuite}
        onClose={() => setSuiteOpen(false)}
        onSaved={absorb}
      />

      <Modal
        open={retiring != null}
        onClose={() => setRetiring(null)}
        title="Take this room out of service?"
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setRetiring(null)} disabled={busy}>
              Cancel
            </Button>
            <Button onClick={retire} disabled={busy}>
              {busy ? "Retiring…" : "Retire room"}
            </Button>
          </>
        }
      >
        <p className="text-[15px] leading-relaxed text-pw-body">
          <span className="font-medium text-pw-ink">{retiring?.name}</span> stops
          being offered when you accept a boarding request. Stays already booked
          into it are not moved, and past stays keep naming it — a room is never
          deleted, only closed. You can reopen it here at any time.
        </p>
      </Modal>
    </StaffLayout>
  );
}
