import { useCallback, useEffect, useState } from "react";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell,
} from "recharts";
import {
  FiHome, FiUsers, FiFileText, FiHeart, FiAlertTriangle, FiCheck, FiX, FiPlus, FiUserPlus,
} from "react-icons/fi";
import AdminLayout from "../../components/admin/AdminLayout";
import StatTile from "../../components/admin/StatTile";
import DataTable from "../../components/admin/DataTable";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import { SERIES, APPLICATION_STATUS, AXIS, GRID, TOOLTIP } from "../../lib/chartTheme";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * EVERY NUMBER ON THIS PAGE NOW COMES FROM THE SERVER.
 *
 * It did not. The tiles were the strings "12", "542", "28" and "84"; both
 * charts read hardcoded arrays out of frontend/src/data/admin.js, whose own
 * first line said "Placeholder analytics UNTIL THE REPORTING ENDPOINTS EXIST";
 * Recent Activity rendered a fixed list; and Needs Attention showed a shelter
 * called "Haven Woods (98/100 pets)" that is not in the database, beside
 * approve and reject buttons for a "Maria G." who does not exist and which had
 * no onClick at all.
 *
 * The endpoints had all shipped. GET /admin/analytics has served the charts
 * and the tiles since the analytics module landed; GET /admin/activity is new
 * with V19 and reads audit_log, the table V1 created for this exact panel and
 * that nothing had ever written to.
 *
 * WHAT IS STILL NOT HERE, so nobody has to wonder: a network-wide "needs
 * attention" list is computed from rows this page already has -- shelters over
 * capacity, and staff awaiting approval -- rather than from an endpoint of its
 * own. There is no such endpoint and inventing one for two derivable facts
 * would be a query nobody else wants.
 */
const ACTIVITY_ICON = {
  APPLICATION_APPROVED: FiHeart,
  APPLICATION_DECLINED: FiFileText,
  BOARDING_ACCEPTED: FiHome,
  BOARDING_COMPLETED: FiHome,
  PET_ADDED: FiHeart,
  PET_REMOVED: FiX,
  SHELTER_ADDED: FiHome,
  STAFF_APPROVED: FiUserPlus,
  STAFF_REJECTED: FiUserPlus,
};

/**
 * "3 hours ago". Relative, because on a dashboard the question is always how
 * FRESH a line is, and a timestamp makes the reader do the subtraction.
 */
const ago = (iso) => {
  if (!iso) return "";
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  if (mins < 60 * 24) return `${Math.round(mins / 60)}h ago`;
  return `${Math.round(mins / 1440)}d ago`;
};

function StatusPill({ status }) {
  const tone = status === "ACTIVE" ? "bg-green-50 text-green-700" : "bg-amber-50 text-amber-700";
  return <span className={`rounded-pill px-2.5 py-1 text-[11px] font-semibold ${tone}`}>{status}</span>;
}

export default function AdminDashboard() {
  const [shelters, setShelters] = useState([]);
  const [stats, setStats] = useState(null);
  const [activity, setActivity] = useState([]);
  const [pendingStaff, setPendingStaff] = useState([]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState("");

  const load = useCallback(async () => {
    // ALLSETTLED, NOT ALL. Four independent panels: one endpoint being slow or
    // unhappy should cost that panel, not blank the dashboard. Promise.all
    // rejects on the first failure and the page would show nothing because
    // audit_log was empty.
    const [sh, an, ac, us] = await Promise.allSettled([
      api.get("/admin/shelters"),
      api.get("/admin/analytics?months=6"),
      api.get("/admin/activity?limit=8"),
      api.get("/admin/users"),
    ]);
    if (sh.status === "fulfilled") setShelters(sh.value.data);
    if (an.status === "fulfilled") setStats(an.value.data);
    if (ac.status === "fulfilled") setActivity(ac.value.data);
    if (us.status === "fulfilled") {
      setPendingStaff(us.value.data.filter(
        (u) => u.role === "STAFF" && u.approvalStatus === "PENDING"));
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const [sh, an, ac, us] = await Promise.allSettled([
        api.get("/admin/shelters"),
        api.get("/admin/analytics?months=6"),
        api.get("/admin/activity?limit=8"),
        api.get("/admin/users"),
      ]);
      if (cancelled) return;
      if (sh.status === "fulfilled") setShelters(sh.value.data);
      if (an.status === "fulfilled") setStats(an.value.data);
      if (ac.status === "fulfilled") setActivity(ac.value.data);
      if (us.status === "fulfilled") {
        setPendingStaff(us.value.data.filter(
          (u) => u.role === "STAFF" && u.approvalStatus === "PENDING"));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  async function approveStaff(id) {
    setBusy(true);
    setActionError("");
    try {
      await api.post(`/admin/users/${id}/approve`);
      await load();
    } catch (err) {
      setActionError(apiError(err, "Could not approve them."));
    } finally {
      setBusy(false);
    }
  }

  // SHELTERS AT OR OVER THEIR LICENCE, from the rows already fetched.
  // onSiteCount is animals in the building; capacity is what the licence
  // allows. Not petCount, which is the shelter's books and counts animals that
  // are not there.
  const overCapacity = shelters.filter(
    (s) => s.capacity > 0 && s.onSiteCount >= s.capacity * 0.9);

  const byStatus = stats?.applicationsByStatus ?? [];
  const totalApps = byStatus.reduce((sum, d) => sum + d.value, 0);

  return (
    <AdminLayout
      title="Welcome back, Admin"
      subtitle={new Date().toLocaleDateString(undefined, { weekday: "long", year: "numeric", month: "long", day: "numeric" })}
      actions={
        <>
          <Button size="sm" to="/admin/shelters"><FiPlus className="h-4 w-4" /> Add Shelter</Button>
          <Button size="sm" variant="subtle" to="/admin/users"><FiUserPlus className="h-4 w-4" /> Invite Staff</Button>
          <Button size="sm" variant="outline" to="/admin/quiz">Add Quiz Question</Button>
        </>
      }
    >
      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        {/* COUNTED, NOT TYPED. These were the literal strings "12", "542",
            "28" and "84". Shelters comes from the list this page already has;
            the other three from /admin/analytics, whose Tile carries the
            figure AND its change against the previous window. */}
        <StatTile icon={FiHome} value={shelters.length} label="Total Shelters" />
        <StatTile icon={FiUsers} value={stats?.activePets.value ?? "—"}
          label="Active Pets" delta={stats?.activePets.delta} />
        <StatTile icon={FiFileText} value={stats?.applications.value ?? "—"}
          label="Applications (6mo)" delta={stats?.applications.delta} tone="warning" />
        <StatTile icon={FiHeart} value={stats?.adoptions.value ?? "—"}
          label="Adoptions (6mo)" delta={stats?.adoptions.delta} tone="good" />
      </div>

      <div className="mt-6 grid gap-5 lg:grid-cols-3">
        {/* Change over time -> bars on a time axis. ONE series, so no legend:
            the heading already names what is plotted. */}
        <Card className="p-6 lg:col-span-2">
          <h2 className="text-base font-semibold">Adoptions Over Time</h2>
          <p className="mt-1 text-sm text-pw-muted">Completed adoptions per month across the network.</p>
          <div className="mt-6 h-[280px]">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={stats?.adoptionsByMonth ?? []} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid {...GRID} />
                <XAxis dataKey="month" {...AXIS} />
                <YAxis {...AXIS} width={52} />
                <Tooltip {...TOOLTIP} formatter={(v) => [v, "Adoptions"]} />
                {/* 4px rounded data-end, anchored to the baseline. */}
                <Bar dataKey="adoptions" fill={SERIES} radius={[4, 4, 0, 0]} maxBarSize={44} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>

        {/* Parts of one whole -> donut. Three status colours, each printed with
            its label AND its value, so colour never carries the meaning alone
            (the amber sits just under 3:1 against this surface). */}
        <Card className="p-6">
          <h2 className="text-base font-semibold">Applications by Status</h2>
          <div className="relative mt-4 h-[200px]">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={byStatus} dataKey="value" nameKey="name"
                  innerRadius={62} outerRadius={88} paddingAngle={2} stroke="#fff" strokeWidth={2}
                >
                  {byStatus.map((d) => (
                    // ?? SERIES, not a bare .find().color. The server now
                    // supplies the status names, and a status the theme has
                    // never heard of would otherwise throw on .color and take
                    // the whole dashboard down with it.
                    <Cell key={d.name}
                      fill={APPLICATION_STATUS.find((x) => x.key === d.name)?.color ?? SERIES} />
                  ))}
                </Pie>
                <Tooltip {...TOOLTIP} formatter={(v, n) =>
                  [`${v}${totalApps ? ` (${Math.round((v / totalApps) * 100)}%)` : ""}`, n]} />
              </PieChart>
            </ResponsiveContainer>
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-2xl font-semibold tabular-nums text-pw-ink">{totalApps}</span>
              <span className="text-xs text-pw-muted">Total</span>
            </div>
          </div>

          <ul className="mt-5 space-y-2.5">
            {byStatus.map((d) => (
              <li key={d.name} className="flex items-center gap-2.5 text-sm">
                <span className="h-2.5 w-2.5 shrink-0 rounded-full"
                  style={{ background: APPLICATION_STATUS.find((x) => x.key === d.name)?.color ?? SERIES }} />
                <span className="text-pw-body">{d.name}</span>
                <span className="ml-auto tabular-nums font-medium text-pw-ink">{d.value}</span>
                <span className="w-10 text-right tabular-nums text-pw-muted">
                  {totalApps ? `${Math.round((d.value / totalApps) * 100)}%` : "—"}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      {actionError && (
        <p role="alert" className="mt-6 rounded-card bg-rose-50 px-5 py-4 text-sm text-pw-danger">
          {actionError}
        </p>
      )}

      <div className="mt-6 grid gap-5 lg:grid-cols-2">
        {/* ---------------------------------------------- recent activity */}
        {/* READS audit_log, THE TABLE V1 CREATED FOR THIS PANEL. Its comment
            says it "Powers 'Recent Activity' on the admin dashboard" and
            nothing had ever written a row; this list was a fixed array. Each
            line's sentence is written at the moment the thing happened, so it
            does not silently rewrite itself when a pet or shelter is renamed. */}
        <Card className="p-6">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-semibold">Recent Activity</h2>
            <span className="text-xs text-pw-muted">Newest first</span>
          </div>

          {activity.length === 0 ? (
            <p className="mt-6 text-sm text-pw-muted">
              Nothing recorded yet. Approvals, adoptions, boarding decisions and
              pets joining or leaving a shelter appear here as they happen.
            </p>
          ) : (
            <ul className="mt-5 space-y-4">
              {activity.map((a) => {
                // Falls back to a neutral glyph rather than crashing, so
                // adding a new AuditAction needs no frontend change.
                const Icon = ACTIVITY_ICON[a.action] ?? FiFileText;
                return (
                  <li key={a.id} className="flex gap-3.5">
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-pw-input text-pw-button">
                      <Icon className="h-4 w-4" />
                    </span>
                    <div className="min-w-0">
                      <p className="text-sm font-medium leading-snug text-pw-ink">{a.detail}</p>
                      <p className="mt-0.5 text-xs text-pw-muted">
                        {a.actorName} &middot; {ago(a.createdAt)}
                      </p>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>

        {/* ------------------------------------------------ needs attention */}
        {/* DERIVED FROM ROWS THIS PAGE ALREADY HAS, not from an endpoint of
            its own. Both facts -- a shelter near its licence, a staff member
            awaiting approval -- are in /admin/shelters and /admin/users, and a
            third query for two derivable things is one nobody else wants. */}
        <Card className="p-6">
          <h2 className="text-base font-semibold">Needs Attention</h2>

          {overCapacity.length === 0 && pendingStaff.length === 0 ? (
            <p className="mt-6 text-sm text-pw-muted">
              Nothing needs you right now. No shelter is near its licensed
              capacity and no staff member is waiting for approval.
            </p>
          ) : (
            <div className="mt-5 space-y-3">
              {overCapacity.map((s) => (
                <div key={s.id}
                  className="flex items-center gap-3.5 rounded-2xl border border-amber-200 bg-amber-50/60 p-4">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-700">
                    <FiAlertTriangle className="h-4 w-4" />
                  </span>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-pw-ink">
                      {s.onSiteCount >= s.capacity ? "Shelter at capacity" : "Shelter nearly full"}
                    </p>
                    <p className="mt-0.5 text-xs text-pw-muted">
                      {s.name} ({s.onSiteCount}/{s.capacity} on site)
                    </p>
                  </div>
                  <Button size="sm" className="ml-auto shrink-0" to="/admin/shelters">Manage</Button>
                </div>
              ))}

              {pendingStaff.map((u) => (
                <div key={u.id}
                  className="flex items-center gap-3.5 rounded-2xl border border-pw-stroke/50 p-4">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-pw-input text-pw-button">
                    <FiUserPlus className="h-4 w-4" />
                  </span>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-pw-ink">
                      Staff approval: {u.fullName}
                    </p>
                    <p className="mt-0.5 truncate text-xs text-pw-muted">
                      Pending for {u.shelterName ?? "no shelter"}
                    </p>
                  </div>
                  <div className="ml-auto flex shrink-0 gap-2">
                    {/* REJECT IS A LINK, NOT A BUTTON, and that is deliberate.
                        POST /admin/users/{id}/reject requires a reason in the
                        body -- the person is told why -- so a one-click reject
                        here would either send an empty reason or invent one.
                        The Users page has the form. */}
                    <Button size="sm" variant="outline" to="/admin/users"
                      aria-label={`Review ${u.fullName}`}>
                      <FiX className="h-4 w-4" />
                    </Button>
                    <Button size="sm" disabled={busy}
                      onClick={() => approveStaff(u.id)}
                      aria-label={`Approve ${u.fullName}`}>
                      <FiCheck className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      <div className="mt-6">
        <DataTable
          // The network only grows, and this is the dashboard's tallest panel.
          pageSize={6}
          title="Shelters Overview"
          columns={[
            { key: "name", label: "Shelter" },
            { key: "location", label: "Location" },
            { key: "petCount", label: "Pets", align: "right" },
            { key: "staffCount", label: "Staff", align: "right" },
            { key: "status", label: "Status" },
          ]}
          rows={shelters.slice(0, 3)}
          render={(row, key) => {
            if (key === "status") return <StatusPill status={row.status} />;
            if (key === "name") {
              return (
                <span className="flex items-center gap-2.5">
                  <span className="flex h-8 w-8 items-center justify-center rounded-full bg-pw-input text-[11px] font-semibold text-pw-button">
                    {row.name.split(" ").map((w) => w[0]).join("").slice(0, 2)}
                  </span>
                  <span className="font-medium">{row.name}</span>
                </span>
              );
            }
            return row[key];
          }}
        />
      </div>
    </AdminLayout>
  );
}
