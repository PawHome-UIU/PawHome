import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  FiPlus, FiHome, FiCheckCircle, FiUsers, FiAlertTriangle,
  FiCheck, FiX, FiMoreVertical, FiRefreshCw,
} from "react-icons/fi";
import AdminLayout from "../../components/admin/AdminLayout";
import StatTile from "../../components/admin/StatTile";
import DataTable from "../../components/admin/DataTable";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Tabs from "../../components/ui/Tabs";
import AddShelterModal from "../../components/admin/AddShelterModal";
import SuccessModal from "../../components/ui/SuccessModal";
import SuiteModal from "../../components/shelter/SuiteModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * The shelter network.
 *
 * READS THE REAL DATABASE. It used to render a five-row array from
 * data/admin.js -- which looked like a working network and was connected to
 * nothing, so "Add Shelter" added a row to a modal's memory and the staff
 * registration dropdown offered shelters that did not exist.
 *
 * Occupancy and staff counts are COUNTED BY THE SERVER on every request, not
 * stored. A stored occupancy is wrong the first time a pet is adopted by any
 * code path that forgets to decrement it.
 */
function StatusPill({ status }) {
  const tones = {
    ACTIVE: "bg-green-50 text-green-700",
    PENDING: "bg-amber-50 text-amber-700",
    INACTIVE: "bg-pw-input text-pw-muted",
  };
  return (
    <span className={`rounded-pill px-2.5 py-1 text-[11px] font-semibold ${tones[status]}`}>
      {status}
    </span>
  );
}

/** Occupancy reads as a number AND a bar AND a label -- never the bar alone. */
function CapacityBar({ pets, capacity }) {
  // Capacity is NOT NULL and > 0 in the schema, but a division here would be
  // the one place a bad row turns into NaN on screen.
  const pct = capacity > 0 ? Math.round((pets / capacity) * 100) : 0;
  const full = pct >= 90;
  return (
    <div className="min-w-[150px]">
      <div className="flex items-center justify-between text-xs">
        <span className={full ? "font-semibold text-rose-600" : "text-pw-muted"}>{pct}%</span>
        <span className="tabular-nums text-pw-muted">{pets}/{capacity}</span>
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-pw-input">
        <div
          className={`h-full rounded-full ${full ? "bg-rose-500" : "bg-pw-button"}`}
          // An over-full shelter is real (capacity can be reduced below the
          // current headcount), so the bar is clamped but the number is not.
          style={{ width: `${Math.min(pct, 100)}%` }}
        />
      </div>
    </div>
  );
}

export default function SheltersPage() {
  const [shelters, setShelters] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [selectedId, setSelectedId] = useState(null);
  const [tab, setTab] = useState("overview");

  // Each of these remembers WHICH shelter it was fetched for. Deriving the
  // visible list from that is what stops the previous shelter's staff showing
  // under the new shelter's name for the length of one request -- and it
  // removes the synchronous clear that would otherwise sit in the effect.
  const [staffFor, setStaffFor] = useState({ id: null, rows: [] });
  const [suitesFor, setSuitesFor] = useState({ id: null, rows: [] });
  // V19. The three /admin/shelters/{id}/suites endpoints had shipped and
  // nothing called them -- an admin could read a shelter's rooms and not
  // change them.
  const [suiteModal, setSuiteModal] = useState(null);   // {suite} or {suite:null}
  const [suiteBusy, setSuiteBusy] = useState(false);
  const [suiteError, setSuiteError] = useState("");

  // Picking a shelter in the table scrolls down to its card. Two refs, not
  // state: the flag must not cause a render, and it exists so ONLY a
  // deliberate click scrolls. The first load and the reselect after a save
  // also change selectedId, and yanking the page around then is jarring.
  const detailRef = useRef(null);
  const scrollOnSelect = useRef(false);

  const [addOpen, setAddOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [notice, setNotice] = useState(null);
  const [busyId, setBusyId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/admin/shelters");
      setShelters(data);
      // Keep the current selection if it survived the reload; otherwise fall
      // back to the first row so the detail card is never left pointing at
      // a shelter that is no longer in the list.
      setSelectedId((prev) =>
        prev && data.some((s) => s.id === prev) ? prev : data[0]?.id ?? null);
    } catch (err) {
      setLoadError(apiError(err, "Could not load the shelter network."));
    } finally {
      setLoading(false);
    }
  }, []);

  // The first load runs inline rather than calling load(), so that no state
  // is set synchronously while the effect body is running. load() stays for
  // the Refresh button and for reloading after a write.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/admin/shelters");
        if (cancelled) return;
        setShelters(data);
        setSelectedId(data[0]?.id ?? null);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the shelter network."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const selected = useMemo(
    () => shelters.find((s) => s.id === selectedId) ?? null,
    [shelters, selectedId]);

  // Staff for the selected shelter. Filtered from the admin users list rather
  // than a second endpoint: that list is already loaded elsewhere in the
  // portal and a per-shelter staff route would return the same rows.
  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    api.get("/admin/users")
      .then(({ data }) => {
        if (cancelled) return;
        setStaffFor({
          id: selectedId,
          rows: data.filter((u) => u.role === "STAFF" && u.shelterId === selectedId),
        });
      })
      .catch(() => { if (!cancelled) setStaffFor({ id: selectedId, rows: [] }); });
    return () => { cancelled = true; };
  }, [selectedId, notice]);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    api.get(`/admin/shelters/${selectedId}/suites`)
      .then(({ data }) => { if (!cancelled) setSuitesFor({ id: selectedId, rows: data }); })
      .catch(() => { if (!cancelled) setSuitesFor({ id: selectedId, rows: [] }); });
    return () => { cancelled = true; };
  }, [selectedId, notice]);

  const staff = staffFor.id === selectedId ? staffFor.rows : [];
  const suites = suitesFor.id === selectedId ? suitesFor.rows : [];

  /** Re-read one shelter's rooms after a change. */
  const reloadSuites = async (sid) => {
    const { data } = await api.get(`/admin/shelters/${sid}/suites`);
    setSuitesFor({ id: sid, rows: data });
  };

  async function retireSuite(row) {
    setSuiteBusy(true);
    setSuiteError("");
    try {
      await api.post(`/admin/shelters/${selectedId}/suites/${row.id}/deactivate`);
      await reloadSuites(selectedId);
    } catch (err) {
      setSuiteError(apiError(err, "Could not retire that room."));
    } finally {
      setSuiteBusy(false);
    }
  }

  function selectShelter(id) {
    scrollOnSelect.current = true;
    setSelectedId(id);
  }

  useEffect(() => {
    if (!scrollOnSelect.current) return;
    scrollOnSelect.current = false;
    // Someone who has asked the OS to reduce motion gets a jump, not a glide.
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    detailRef.current?.scrollIntoView({
      behavior: reduce ? "auto" : "smooth",
      block: "start",
    });
  }, [selectedId]);

  async function save(payload, logo) {
    setSaving(true);
    setSaveError("");
    try {
      // THE LOGO GOES SECOND, AND ONLY IF THE SHELTER SAVED. A new shelter has
      // no id until the POST returns, so there is nothing to attach a file to
      // before this point -- and uploading first would leave an orphaned image
      // whenever the name turned out to be taken.
      let id = editing?.id;
      if (editing) {
        await api.put(`/admin/shelters/${editing.id}`, payload);
        setNotice({ title: "Shelter updated", message: `${payload.name} has been saved.` });
      } else {
        const { data } = await api.post("/admin/shelters", payload);
        id = data.id;
        setSelectedId(data.id);
        setNotice({
          title: "Shelter added",
          message: `${data.name} is now part of the network and can take staff `
                 + `accounts and pet listings.`,
        });
      }

      if (logo && id) {
        const body = new FormData();
        body.append("file", logo);
        // Its own try: the shelter is already saved by now, so a rejected
        // image must not read as "that shelter could not be saved".
        try {
          await api.post(`/admin/shelters/${id}/logo`, body);
        } catch (err) {
          setNotice({
            title: "Saved, but the logo did not upload",
            message: apiError(err, "Try adding the picture again from Edit."),
          });
        }
      }
      setAddOpen(false);
      setEditing(null);
      await load();
    } catch (err) {
      // Stays open with the form intact -- a duplicate name is the common
      // case and retyping seven fields to fix one is unreasonable.
      setSaveError(apiError(err, "That shelter could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  async function setStatus(shelter, status) {
    setBusyId(shelter.id);
    try {
      await api.post(`/admin/shelters/${shelter.id}/status`, { status });
      await load();
      setNotice({
        title: status === "ACTIVE" ? "Shelter reactivated" : "Shelter deactivated",
        message: status === "ACTIVE"
          ? `${shelter.name} is back in the network and open for staff sign-ups.`
          : `${shelter.name} is marked inactive. Nothing was deleted — its pets, `
            + `staff and past adoptions all keep their records.`,
      });
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  async function decideStaff(user, approved) {
    setBusyId(user.id);
    try {
      if (approved) {
        await api.post(`/admin/users/${user.id}/approve`);
      } else {
        await api.post(`/admin/users/${user.id}/reject`, {
          reason: "Your sign-up was not approved by the shelter administrator.",
        });
      }
      setNotice({
        title: approved ? "Staff approved" : "Sign-up rejected",
        message: approved
          ? `${user.fullName} can now sign in and manage this shelter's pets, `
            + `applications and boarding.`
          : `${user.fullName} has been told their sign-up was not approved.`,
      });
      await load();
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  const active = shelters.filter((s) => s.status === "ACTIVE").length;
  const totalStaff = shelters.reduce((n, s) => n + s.staffCount, 0);
  const atCapacity = shelters.filter(
    (s) => s.capacity > 0 && s.petCount / s.capacity >= 0.9).length;
  const pendingStaff = staff.filter((u) => u.approvalStatus === "PENDING").length;

  return (
    <AdminLayout
      title="Manage Shelters"
      subtitle={
        loading ? "Loading the network…"
                : `Currently overseeing ${active} active ${active === 1 ? "shelter" : "shelters"} in the network.`
      }
      actions={
        <>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          <Button size="sm"
            onClick={() => { setEditing(null); setSaveError(""); setAddOpen(true); }}>
            <FiPlus className="h-4 w-4" /> Add Shelter
          </Button>
        </>
      }
    >
      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile icon={FiHome} value={String(shelters.length)} label="Total Shelters" />
        <StatTile icon={FiCheckCircle} value={String(active)} label="Active Shelters" tone="good" />
        <StatTile icon={FiUsers} value={String(totalStaff)} label="Total Staff" />
        <StatTile icon={FiAlertTriangle} value={String(atCapacity)} label="At Capacity" tone="warning" />
      </div>

      {loadError ? (
        <Card className="mt-6 px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      ) : loading ? (
        <Card className="mt-6 px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading shelters…</p>
        </Card>
      ) : shelters.length === 0 ? (
        <Card className="mt-6 px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-ink">No shelters yet.</p>
          <p className="mt-1.5 text-sm text-pw-muted">
            Staff cannot register until at least one shelter exists.
          </p>
          <Button size="sm" className="mt-5" onClick={() => setAddOpen(true)}>
            <FiPlus className="h-4 w-4" /> Add the first shelter
          </Button>
        </Card>
      ) : (
        <>
          {/* The network first: this table is the page's index, and
              picking a row from it chooses the card below. */}
          <div className="mt-6">
            <DataTable
              title="All Shelters Overview"
              pageSize={8}
              columns={[
                { key: "name", label: "Shelter Name" },
                { key: "location", label: "Location" },
                { key: "petCount", label: "Pets", align: "right" },
                { key: "staffCount", label: "Staff", align: "right" },
                { key: "capacity", label: "Capacity" },
                { key: "status", label: "Status" },
              ]}
              rows={shelters}
              render={(row, key) => {
                if (key === "status") return <StatusPill status={row.status} />;
                if (key === "capacity") {
                  return <CapacityBar pets={row.petCount} capacity={row.capacity} />;
                }
                if (key === "name") {
                  // Selecting a row drives the detail card above it.
                  return (
                    <button
                      type="button"
                      onClick={() => selectShelter(row.id)}
                      className={`text-left font-medium hover:underline ${
                        row.id === selectedId ? "text-pw-button" : "text-pw-ink"}`}
                    >
                      {row.name}
                    </button>
                  );
                }
                return row[key];
              }}
            />
          </div>

          {/* scroll-mt-20 clears the sticky 64px header, so the card
              title is not left underneath it after scrollIntoView. */}
          {selected && (
            <div ref={detailRef} className="mt-6 scroll-mt-20">
              <Card className="overflow-hidden">
                <div className="flex flex-wrap items-center justify-between gap-4 bg-pw-sidebar px-6 py-5">
                  <div className="flex items-center gap-4">
                    {/* The logo, once one has been uploaded. No placeholder
                        block when there is none: an empty white square beside
                        the name reads as a broken image rather than as an
                        absence. */}
                    {selected.logoPath && (
                      <span className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-2xl bg-white">
                        <img src={`${API_BASE}/files/${selected.logoPath}`} alt=""
                          className="h-full w-full object-contain" />
                      </span>
                    )}
                    <div>
                      <h2 className="text-xl font-semibold text-white">{selected.name}</h2>
                      <p className="mt-0.5 text-sm text-white/85">{selected.location}</p>
                    </div>
                  </div>
                  <div className="flex gap-3">
                    <Button size="sm" variant="white"
                      onClick={() => { setEditing(selected); setSaveError(""); setAddOpen(true); }}>
                      Edit
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busyId === selected.id}
                      onClick={() => setStatus(
                        selected, selected.status === "INACTIVE" ? "ACTIVE" : "INACTIVE")}
                      className="!border-white/70 !bg-transparent !text-white hover:!bg-white/15"
                    >
                      {selected.status === "INACTIVE" ? "Reactivate" : "Deactivate"}
                    </Button>
                  </div>
                </div>

                <div className="px-6 pt-5">
                  <Tabs
                    value={tab}
                    onChange={setTab}
                    tabs={[
                      { key: "overview", label: "Overview" },
                      { key: "suites", label: "Boarding Suites", count: suites.length },
                      { key: "staff", label: "Staff Accounts", count: staff.length },
                      { key: "pets", label: "Pets", count: selected.petCount },
                    ]}
                  />
                </div>

                <div className="px-6 py-6">
                  {tab === "overview" && (
                    <dl className="grid gap-6 sm:grid-cols-3">
                      {[
                        ["Capacity", `${selected.petCount} / ${selected.capacity}`],
                        ["Boarding rate", `৳${Number(selected.boardingRateDaily).toFixed(2)} / night`],
                        // V13. Zero is not a price, it is an opt-out -- saying
                        // "৳0.00 / month" would read as free daycare.
                        ["Daycare", Number(selected.daycareMonthlyPrice) > 0
                          ? `৳${Number(selected.daycareMonthlyPrice).toFixed(2)} / month · `
                            + `${selected.daycareSlotCapacity} per band`
                          : "Not offered"],
                        ["Contact", selected.email],
                        ["Phone", selected.phone],
                        ["Address", `${selected.addressLine}, ${selected.location} ${selected.zip}`],
                        // A pill here too. The same value rendered as bare
                        // text in one place and a pill in another reads as
                        // two different kinds of thing.
                        ["Status", <StatusPill key="s" status={selected.status} />],
                      ].map(([k, v]) => (
                        <div key={k}>
                          <dt className="text-xs font-semibold uppercase tracking-wider text-pw-muted">{k}</dt>
                          <dd className="mt-1.5 text-[15px] text-pw-ink">{v}</dd>
                        </div>
                      ))}
                      {selected.description && (
                        <div className="sm:col-span-3">
                          <dt className="text-xs font-semibold uppercase tracking-wider text-pw-muted">About</dt>
                          <dd className="mt-1.5 text-[15px] leading-relaxed text-pw-body">
                            {selected.description}
                          </dd>
                        </div>
                      )}
                    </dl>
                  )}

                  {tab === "suites" && (
                    <>
                    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                      <h3 className="text-base font-semibold">Boarding Suites</h3>
                      <Button size="sm" onClick={() => setSuiteModal({ suite: null })}>
                        <FiPlus className="h-4 w-4" /> Add Room
                      </Button>
                    </div>
                    {suiteError && (
                      <p role="alert" className="mb-3 rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
                        {suiteError}
                      </p>
                    )}
                    {suites.length === 0 ? (
                      <p className="py-8 text-center text-sm text-pw-muted">
                        No boarding suites yet. A boarding request cannot be assigned
                        a room until this shelter has one.
                      </p>
                    ) : (
                      <DataTable
                        // A shelter with forty kennels would otherwise push
                        // the staff tab off the bottom of the drawer.
                        pageSize={6}
                        columns={[
                          { key: "name", label: "Suite" },
                          { key: "species", label: "Takes" },
                          // V16. A room holds more than one animal, and the
                          // daycare band capacity is sold against the sum of
                          // this column.
                          { key: "capacity", label: "Places", align: "right" },
                          { key: "active", label: "Status" },
                          { key: "actions", label: "", align: "right" },
                        ]}
                        rows={suites}
                        render={(row, key) => {
                          // NULL species means "any", which is a different
                          // statement from "not decided" -- so it is spelled out
                          // rather than left blank.
                          if (key === "species") {
                            return row.species
                              ? row.species.charAt(0) + row.species.slice(1).toLowerCase()
                              : <span className="text-pw-muted">Any species</span>;
                          }
                          if (key === "active") {
                            return <StatusPill status={row.active ? "ACTIVE" : "INACTIVE"} />;
                          }
                          if (key === "actions") {
                            return (
                              <div className="flex justify-end gap-2">
                                <Button size="sm" variant="outline" disabled={suiteBusy}
                                  onClick={() => setSuiteModal({ suite: row })}>
                                  Edit
                                </Button>
                                {/* RETIRE, NOT DELETE. boarding_requests.suite_id
                                    references this row, so a past stay still
                                    names the room it was in -- the endpoint is
                                    /deactivate for that reason, and there is no
                                    delete to call. */}
                                {row.active && (
                                  <Button size="sm" variant="subtle" disabled={suiteBusy}
                                    onClick={() => retireSuite(row)}>
                                    Retire
                                  </Button>
                                )}
                              </div>
                            );
                          }
                          return row[key];
                        }}
                      />
                    )}
                    </>
                  )}

                  {tab === "staff" && (
                    <>
                      <div className="mb-4 flex items-center justify-between">
                        <h3 className="text-base font-semibold">
                          Staff Members
                          {pendingStaff > 0 && (
                            <span className="ml-2 rounded-pill bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-700">
                              {pendingStaff} awaiting approval
                            </span>
                          )}
                        </h3>
                        <Button size="sm" variant="subtle" to="/admin/users">Invite Staff</Button>
                      </div>

                      {staff.length === 0 ? (
                        <p className="py-8 text-center text-sm text-pw-muted">
                          Nobody has registered as staff at this shelter yet.
                        </p>
                      ) : (
                        <DataTable
                          pageSize={6}
                          columns={[
                            { key: "fullName", label: "Name" },
                            { key: "jobTitle", label: "Role" },
                            { key: "email", label: "Email" },
                            { key: "approvalStatus", label: "Status" },
                            { key: "actions", label: "Actions", align: "right" },
                          ]}
                          rows={staff}
                          render={(row, key) => {
                            if (key === "approvalStatus") return <StatusPill status={row.approvalStatus} />;
                            if (key === "email") return <span className="text-pw-muted">{row.email}</span>;
                            if (key === "jobTitle") {
                              return row.jobTitle ?? <span className="text-pw-muted">—</span>;
                            }
                            if (key === "actions") {
                              return row.approvalStatus === "PENDING" ? (
                                <span className="flex justify-end gap-2">
                                  <Button size="sm" disabled={busyId === row.id}
                                    onClick={() => decideStaff(row, true)}
                                    className="!h-8 !bg-green-600 !px-3 hover:!bg-green-700">
                                    <FiCheck className="h-3.5 w-3.5" /> Approve
                                  </Button>
                                  <Button size="sm" disabled={busyId === row.id}
                                    onClick={() => decideStaff(row, false)}
                                    className="!h-8 !bg-rose-600 !px-3 hover:!bg-rose-700">
                                    <FiX className="h-3.5 w-3.5" /> Reject
                                  </Button>
                                </span>
                              ) : (
                                <button type="button"
                                  className="ml-auto flex h-8 w-8 items-center justify-center rounded-full text-pw-muted hover:bg-pw-input"
                                  aria-label={`Actions for ${row.fullName}`}>
                                  <FiMoreVertical className="h-4 w-4" />
                                </button>
                              );
                            }
                            return row[key];
                          }}
                        />
                      )}
                    </>
                  )}

                  {tab === "pets" && (
                    <p className="py-8 text-center text-sm text-pw-muted">
                      Pet management for this shelter lands with the staff portal.
                    </p>
                  )}
                </div>
              </Card>
            </div>
          )}
        </>
      )}

      {/* Mounted only while open. That is what makes the modal's useState
          initialiser run once per opening, instead of an effect copying the
          props in after the fact. */}
      {addOpen && (
        <AddShelterModal
          open
          shelter={editing}
          busy={saving}
          error={saveError}
          onClose={() => { setAddOpen(false); setEditing(null); setSaveError(""); }}
          onSave={save}
        />
      )}

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? "The change has been saved."}
      />
    {/* SHARED WITH THE STAFF PORTAL. Same three operations, different base
        path: staff reach them through /shelters/mine/suites, which takes the
        shelter from their account; an admin has none, so the id is named. */}
    {suiteModal && (
      <SuiteModal
        key={suiteModal.suite?.id ?? "new"}
        open
        suite={suiteModal.suite}
        basePath={`/admin/shelters/${selectedId}`}
        onClose={() => setSuiteModal(null)}
        onSaved={() => reloadSuites(selectedId)}
      />
    )}

    </AdminLayout>
  );
}
