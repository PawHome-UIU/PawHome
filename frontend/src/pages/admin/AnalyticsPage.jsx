import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell,
} from "recharts";
import { FiHeart, FiFileText, FiCheckCircle, FiUsers, FiFile } from "react-icons/fi";
import { useEffect, useState } from "react";
import AdminLayout from "../../components/admin/AdminLayout";
import Dropdown from "../../components/ui/Dropdown";
import StatTile from "../../components/admin/StatTile";
import DataTable from "../../components/admin/DataTable";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { SERIES, APPLICATION_STATUS, PET_STATUS, AXIS, GRID, TOOLTIP } from "../../lib/chartTheme";

/**
 * Network analytics.
 *
 * EVERY FIGURE ON THIS PAGE USED TO BE INVENTED. data/admin.js held 1,482
 * adoptions, 5,204 applications, a 68.5% approval rate and six months of bars
 * for months that never happened. It rendered convincingly and an admin could
 * repeat any of it in a meeting. All of it now comes from one request.
 *
 * ONE REQUEST, NOT SEVEN. Each chart describes the same period and the same
 * shelter filter, so they are fetched together -- seven calls would give the
 * donut and the tile beside it seven chances to disagree.
 */

const RANGES = [
  { value: 6, label: "Last 6 months" },
  { value: 12, label: "Last 12 months" },
  { value: 24, label: "Last 24 months" },
];

/** The four keys the stacked bars read, in stack order. */
const SPECIES_KEYS = { Available: "available", Pending: "pending", Adopted: "adopted", Unavailable: "unavailable" };

const money = (n) =>
  n == null ? "--" : `৳${Number(n).toFixed(2)}`;

export default function AnalyticsPage() {
  const [months, setMonths] = useState(6);
  const [shelter, setShelter] = useState(null);
  const [shelters, setShelters] = useState([]);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  // The shelter list is fetched once; it does not change when the filters do.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data: rows } = await api.get("/admin/shelters");
        if (!cancelled) {
          setShelters(rows.map((s) => ({ value: s.id, label: s.name })));
        }
      } catch {
        // A missing shelter list costs the filter, not the page. The charts
        // below still render for the whole network.
      }
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const params = { months };
        if (shelter != null) params.shelter = shelter;
        const { data: rows } = await api.get("/admin/analytics", { params });
        if (cancelled) return;
        setData(rows);
        setError("");
      } catch (err) {
        if (cancelled) return;
        setError(apiError(err, "The figures could not be loaded."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [months, shelter]);

  const byStatus = data?.applicationsByStatus ?? [];
  const totalApps = byStatus.reduce((s, d) => s + d.value, 0);
  const byShelter = data?.adoptionsByShelter ?? [];
  // Guard the denominator: Math.max of an empty list is -Infinity, and every
  // bar would come out NaN% wide.
  const maxShelter = byShelter.length ? Math.max(...byShelter.map((d) => d.adoptions)) : 0;

  // Recharts reads its series by key, so the four counts are flattened onto
  // each row under the PET_STATUS names the legend and colours already use.
  const species = (data?.petsBySpecies ?? []).map((r) => ({
    species: r.species,
    ...Object.fromEntries(PET_STATUS.map((s) => [s.key, r[SPECIES_KEYS[s.key]]])),
  }));

  const rangeLabel = RANGES.find((r) => r.value === months)?.label ?? "Last 6 months";

  return (
    <AdminLayout
      title="Analytics"
      subtitle="Measuring the joy of every successful connection."
      actions={
        <>
          {/* Filters sit in one row above the charts. Dropdown, not a bare
              <select>: the browser's own control brings its own font, arrow and
              focus ring, and next to the designed pills it reads as a piece of
              a different application. */}
          <Dropdown
            variant="bar" align="right" label={rangeLabel}
            value={months} onChange={(v) => setMonths(v ?? 6)}
            options={RANGES}
          />
          <Dropdown
            variant="bar" align="right" label="All Shelters"
            value={shelter} onChange={setShelter} anyLabel="All Shelters"
            options={shelters}
          />
          <Button size="sm" to="/admin/reports"><FiFile className="h-4 w-4" /> Generate Report</Button>
        </>
      }
    >
      {error && (
        <Card className="mb-5 border-pw-danger/40 bg-rose-50/60 p-4">
          <p className="text-sm text-pw-danger">{error}</p>
        </Card>
      )}

      {/* The whole page is one request, so it has one loading state. Charts
          that appear one at a time as separate calls land is worse than a
          moment of nothing. */}
      {loading && !data ? (
        <p className="py-24 text-center text-sm text-pw-muted">Counting…</p>
      ) : data && (
        <>
          <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
            {/* delta is null when the previous period had nothing to compare
                against, and StatTile then shows no badge -- see the service. */}
            <StatTile icon={FiHeart} value={data.adoptions.value}
              label="Adoptions" delta={data.adoptions.delta ?? undefined} />
            <StatTile icon={FiFileText} value={data.applications.value}
              label="Applications" delta={data.applications.delta ?? undefined} />
            <StatTile icon={FiCheckCircle} value={data.approvalRate.value}
              label="Approval Rate" delta={data.approvalRate.delta ?? undefined} tone="good" />
            <StatTile icon={FiUsers} value={data.activePets.value} label="Pets on the Catalogue" />
          </div>

          <div className="mt-6 grid gap-5 lg:grid-cols-3">
            <Card className="p-6 lg:col-span-2">
              <h2 className="text-base font-semibold">Adoptions Over Time</h2>
              <div className="mt-6 h-[260px]">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={data.adoptionsByMonth} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                    <CartesianGrid {...GRID} />
                    <XAxis dataKey="month" {...AXIS} />
                    <YAxis {...AXIS} width={52} allowDecimals={false} />
                    <Tooltip {...TOOLTIP} formatter={(v) => [v, "Adoptions"]} />
                    <Bar dataKey="adoptions" fill={SERIES} radius={[4, 4, 0, 0]} maxBarSize={40} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Card>

            <Card className="p-6">
              <h2 className="text-base font-semibold">Applications by Status</h2>
              {totalApps === 0 ? (
                <p className="py-20 text-center text-sm text-pw-muted">
                  No applications in this period.
                </p>
              ) : (
                <>
                  <div className="relative mt-4 h-[190px]">
                    <ResponsiveContainer width="100%" height="100%">
                      <PieChart>
                        <Pie data={byStatus} dataKey="value" nameKey="name"
                          innerRadius={58} outerRadius={84} paddingAngle={2} stroke="#fff" strokeWidth={2}>
                          {byStatus.map((d) => (
                            <Cell key={d.name} fill={APPLICATION_STATUS.find((s) => s.key === d.name).color} />
                          ))}
                        </Pie>
                        <Tooltip {...TOOLTIP} formatter={(v, n) => [`${v} (${Math.round((v / totalApps) * 100)}%)`, n]} />
                      </PieChart>
                    </ResponsiveContainer>
                    <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                      <span className="text-2xl font-semibold tabular-nums text-pw-ink">{totalApps}</span>
                      <span className="text-xs text-pw-muted">Total</span>
                    </div>
                  </div>
                  {/* The legend carries the value beside every label. That is
                      also the relief the amber Pending fill owes: it sits under
                      3:1 against white, so identity never rests on the fill. */}
                  <ul className="mt-4 space-y-2.5">
                    {byStatus.map((d) => (
                      <li key={d.name} className="flex items-center gap-2.5 text-sm">
                        <span className="h-2.5 w-2.5 shrink-0 rounded-full"
                          style={{ background: APPLICATION_STATUS.find((s) => s.key === d.name).color }} />
                        <span className="text-pw-body">{d.name}</span>
                        <span className="ml-auto tabular-nums font-medium text-pw-ink">{d.value}</span>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </Card>
          </div>

          <div className="mt-6 grid gap-5 lg:grid-cols-2">
            {/* Ranking -> horizontal bars, sorted, each directly labelled. A
                chart library is overkill for four rows and would read worse. */}
            <Card className="p-6">
              <h2 className="text-base font-semibold">Adoptions by Shelter</h2>
              {/* Not narrowed by the shelter filter, deliberately: this chart
                  IS the comparison between shelters, and one bar at 100% of
                  itself says nothing. */}
              <p className="mt-1 text-xs text-pw-muted">Across the whole network.</p>
              {byShelter.length === 0 ? (
                <p className="py-16 text-center text-sm text-pw-muted">No shelters yet.</p>
              ) : (
                <ul className="mt-6 space-y-4">
                  {byShelter.map((d) => (
                    <li key={d.shelter}>
                      <div className="flex items-baseline justify-between text-sm">
                        <span className="text-pw-body">{d.shelter}</span>
                        <span className="tabular-nums font-medium text-pw-ink">{d.adoptions}</span>
                      </div>
                      <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-pw-input">
                        <div className="h-full rounded-full"
                          style={{
                            width: maxShelter ? `${(d.adoptions / maxShelter) * 100}%` : "0%",
                            background: SERIES,
                          }} />
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Card>

            {/* Composition within a category -> stacked bars. FOUR series, so a
                legend is mandatory; the 2px white gap between segments keeps the
                boundary readable when two fills are close in value. */}
            <Card className="p-6">
              <h2 className="text-base font-semibold">Pets by Species &amp; Status</h2>
              <p className="mt-1 text-xs text-pw-muted">
                Shelter animals only — pets people own from home are not counted.
              </p>
              {species.length === 0 ? (
                <p className="py-16 text-center text-sm text-pw-muted">No pets yet.</p>
              ) : (
                <>
                  <div className="mt-6 h-[260px]">
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={species} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                        <CartesianGrid {...GRID} />
                        <XAxis dataKey="species" {...AXIS} />
                        <YAxis {...AXIS} width={52} allowDecimals={false} />
                        <Tooltip {...TOOLTIP} />
                        {PET_STATUS.map((s, i) => (
                          <Bar key={s.key} dataKey={s.key} stackId="pets" fill={s.color}
                            stroke="#fff" strokeWidth={2}
                            radius={i === PET_STATUS.length - 1 ? [4, 4, 0, 0] : 0}
                            maxBarSize={64} />
                        ))}
                      </BarChart>
                    </ResponsiveContainer>
                  </div>

                  {/* Hand-rolled legend. Recharts ordered its own alphabetically,
                      which matches neither the data order nor the visual stack --
                      and a legend whose order disagrees with the bars is worse
                      than none. Listed top-of-stack first, so reading the legend
                      downward matches reading a bar downward. */}
                  <ul className="mt-4 flex flex-wrap justify-center gap-x-6 gap-y-2">
                    {[...PET_STATUS].reverse().map((s) => (
                      <li key={s.key} className="flex items-center gap-2 text-sm text-pw-body">
                        <span className="h-2.5 w-2.5 rounded-full" style={{ background: s.color }} />
                        {s.key}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </Card>
          </div>

          <div className="mt-6">
            <DataTable
              title="Adoption Records"
              empty="No adoptions in this period."
              pageSize={8}
              columns={[
                { key: "pet", label: "Pet" },
                { key: "shelter", label: "Shelter" },
                { key: "adopter", label: "Adopter" },
                { key: "date", label: "Date" },
                { key: "fee", label: "Fee", align: "right" },
                { key: "days", label: "Days to place", align: "right" },
              ]}
              rows={data.adoptionRecords}
              render={(row, key) => {
                if (key === "pet") return <span className="font-medium">{row.pet}</span>;
                if (key === "fee") return money(row.fee);
                // Days from submission to adoption. Same-day shows "0d",
                // which is a real answer and not a missing one -- but a
                // NEGATIVE number is not an answer at all. It means the
                // adoption is dated before its own application, which the
                // schema cannot forbid (a CHECK cannot span two tables), so
                // rather than print "-28d" the cell says nothing.
                if (key === "days") return row.days < 0 ? "--" : `${row.days}d`;
                if (key === "date") {
                  return new Date(`${row.date}T00:00:00`).toLocaleDateString(
                    undefined, { month: "short", day: "numeric", year: "numeric" });
                }
                return row[key];
              }}
            />
          </div>
        </>
      )}
    </AdminLayout>
  );
}
