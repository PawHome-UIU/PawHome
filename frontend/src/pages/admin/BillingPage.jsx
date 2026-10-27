import { useCallback, useEffect, useState } from "react";
import { FiFileText, FiRefreshCw, FiHome, FiClock, FiTrendingUp } from "react-icons/fi";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import { Select } from "../../components/ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * What the whole network has billed.
 *
 * WHY THIS PAGE EXISTS. AdminSlipController has shipped three finished
 * endpoints since V13 and nothing called any of them, so the person who owns
 * the network could not see a single bill. Staff could see their own shelter's
 * takings; the admin above them could see none, and /staff/slips is denied to
 * admins by the filter chain, so there was no way round it.
 *
 * READ-ONLY, AND DELIBERATELY. There is no "issue a slip" button here for the
 * reason AdminSlipController states: a boarding slip is written by checking a
 * pet out and a daycare slip by the monthly job. ck_slip_source would refuse a
 * row that pointed at neither, so a hand-issued bill is not a feature that was
 * left out -- it is one the schema forbids.
 *
 * THE TOTAL IS INVOICED, NOT COLLECTED. PawHome tracks no payments at all. The
 * strip says so, because a figure labelled only "Total" on an admin dashboard
 * gets read as money in the bank.
 *
 * THE SHELTER FILTER IS THIS PAGE'S ONE PRIVILEGE. Staff get their own shelter
 * from their account and cannot ask about another; an admin has no shelter, so
 * naming one is the only way to narrow the list -- see the note on
 * AdminSlipController.shelterId.
 */
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

const money = (n) => `৳${Number(n ?? 0).toLocaleString(undefined,
  { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function AdminBillingPage() {
  const [slips, setSlips] = useState([]);
  const [totals, setTotals] = useState(null);
  const [shelters, setShelters] = useState([]);
  const [shelterId, setShelterId] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const load = useCallback(async (sid) => {
    setLoading(true);
    setLoadError("");
    // Empty string means "every shelter". Sent as an absent parameter rather
    // than as shelterId=, which Spring would bind as a blank Long and reject.
    const q = sid ? `?shelterId=${sid}` : "";
    try {
      const [listRes, totalRes, shelterRes] = await Promise.all([
        api.get(`/admin/slips${q}`),
        api.get(`/admin/slips/totals${q}`),
        api.get("/admin/shelters"),
      ]);
      setSlips(listRes.data);
      setTotals(totalRes.data);
      setShelters(shelterRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load the bills."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const q = shelterId ? `?shelterId=${shelterId}` : "";
      try {
        const [listRes, totalRes, shelterRes] = await Promise.all([
          api.get(`/admin/slips${q}`),
          api.get(`/admin/slips/totals${q}`),
          api.get("/admin/shelters"),
        ]);
        if (cancelled) return;
        setSlips(listRes.data);
        setTotals(totalRes.data);
        setShelters(shelterRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the bills."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [shelterId]);

  return (
    <AdminLayout
      title="Billing"
      subtitle="Every bill the network has issued. Invoiced, not collected."
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <Select className="w-52" value={shelterId}
            onChange={(e) => setShelterId(e.target.value)}>
            <option value="">All shelters</option>
            {shelters.map((s) => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </Select>
          <Button variant="outline" size="sm" onClick={() => load(shelterId)} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
        </div>
      }
    >
      {loadError && (
        <p role="alert" className="mb-6 rounded-card bg-rose-50 px-5 py-4 text-sm text-pw-danger">
          {loadError}
        </p>
      )}

      {totals && (
        <div className="mb-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <Card tinted className="p-5">
            <p className="flex items-center gap-2 text-sm text-pw-body">
              <FiTrendingUp className="h-4 w-4 text-pw-button" /> Billed in total
            </p>
            <p className="mt-1 text-2xl font-semibold text-pw-ink">{money(totals.total)}</p>
            <p className="mt-1 text-xs text-pw-muted">Invoiced, not collected.</p>
          </Card>
          <Card className="p-5">
            <p className="text-sm text-pw-body">Bills issued</p>
            <p className="mt-1 text-2xl font-semibold text-pw-ink">{totals.slips}</p>
          </Card>
          <Card className="p-5">
            <p className="flex items-center gap-2 text-sm text-pw-body">
              <FiHome className="h-4 w-4 text-pw-button" /> Boarding
            </p>
            <p className="mt-1 text-2xl font-semibold text-pw-ink">{totals.boardingSlips}</p>
          </Card>
          <Card className="p-5">
            <p className="flex items-center gap-2 text-sm text-pw-body">
              <FiClock className="h-4 w-4 text-pw-button" /> Daycare
            </p>
            <p className="mt-1 text-2xl font-semibold text-pw-ink">{totals.daycareSlips}</p>
          </Card>
        </div>
      )}

      <DataTable
        title="Bills"
        pageSize={10}
        // The filter changes which rows these are, so page 3 of all shelters
        // must not become page 3 of one of them.
        resetKey={shelterId || "all"}
        empty={loading ? "Loading…" : "No bills issued yet."}
        columns={[
          { key: "slipNo", label: "Slip" },
          { key: "kind", label: "Kind" },
          { key: "shelterName", label: "Shelter" },
          { key: "petName", label: "For" },
          { key: "period", label: "Period" },
          { key: "units", label: "Qty", align: "right" },
          { key: "total", label: "Total", align: "right" },
        ]}
        rows={slips}
        render={(row, key) => {
          if (key === "slipNo") {
            return <span className="font-mono text-xs">{row.slipNo}</span>;
          }
          if (key === "kind") {
            return (
              <span className={`rounded-pill px-2.5 py-1 text-[10px] font-medium ${
                row.kind === "BOARDING" ? "bg-sky-50 text-sky-700" : "bg-pw-input text-pw-button"}`}>
                {row.kind === "BOARDING" ? "boarding" : "daycare"}
              </span>
            );
          }
          if (key === "petName") {
            return (
              <>
                {row.petName}
                <span className="block text-xs text-pw-muted">{row.ownerName}</span>
              </>
            );
          }
          if (key === "period") {
            return (
              <>
                {day(row.periodFrom)} – {day(row.periodTo)}
                {row.slotLabel && (
                  <span className="block text-xs text-pw-muted">{row.slotLabel}</span>
                )}
              </>
            );
          }
          if (key === "units") return `${row.units} ${row.unitLabel}`;
          if (key === "total") {
            return <span className="font-medium">{money(row.total)}</span>;
          }
          return row[key];
        }}
      />

      <p className="mt-4 flex items-start gap-2 text-xs text-pw-muted">
        <FiFileText className="mt-0.5 h-3.5 w-3.5 shrink-0" />
        A boarding bill is written when staff check a pet out; a daycare bill on
        the 1st of each month. Neither can be issued by hand — every slip has to
        point at a stay or a subscription.
      </p>
    </AdminLayout>
  );
}
