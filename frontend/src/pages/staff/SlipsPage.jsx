import { useCallback, useEffect, useState } from "react";
import { FiFileText, FiRefreshCw, FiHome, FiClock } from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Pagination from "../../components/ui/Pagination";
import { usePaged } from "../../lib/usePaged";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import SectionTabs from "../../components/portal/SectionTabs";
import { STAFF_BOARDING_TABS } from "../../lib/navTabs";

/**
 * What this shelter has billed.
 *
 * READ-ONLY, AND THERE IS NO "ISSUE A SLIP" BUTTON. A boarding slip is written
 * by checking the pet out; a daycare slip by the monthly job. A hand-issued
 * one would be a bill with no stay and no month behind it, and ck_slip_source
 * would refuse the row anyway -- every slip must point at a boarding request
 * or a subscription.
 *
 * THE TOTAL IS WHAT WAS BILLED, NOT WHAT WAS TAKEN. PawHome has no payment
 * tracking at all, so this figure is revenue invoiced, not revenue received.
 * The strip says so, because a number labelled only "Total" on a shelter's
 * dashboard will be read as cash in the drawer.
 */
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

const money = (n) => `৳${Number(n ?? 0).toLocaleString(undefined,
  { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function StaffSlipsPage() {
  const [slips, setSlips] = useState([]);
  const [totals, setTotals] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [listRes, totalRes] = await Promise.all([
        api.get("/staff/slips"),
        api.get("/staff/slips/totals"),
      ]);
      setSlips(listRes.data);
      setTotals(totalRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load the bills."));
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
        const [listRes, totalRes] = await Promise.all([
          api.get("/staff/slips"),
          api.get("/staff/slips/totals"),
        ]);
        if (cancelled) return;
        setSlips(listRes.data);
        setTotals(totalRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the bills."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // A shelter's bills only ever accumulate, so this is the table that needed
  // paging most and had none at all.
  const pager = usePaged(slips, 10);

  return (
    <StaffLayout
      title="Bills"
      subtitle="Every slip this shelter has issued."
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

      {totals && (
        <div className="mb-8 grid gap-4 sm:grid-cols-3">
          <Card tinted className="p-5">
            <p className="text-sm text-pw-body">Billed in total</p>
            <p className="mt-1 text-2xl font-semibold text-pw-ink">{money(totals.total)}</p>
            <p className="mt-1 text-xs text-pw-muted">Invoiced, not collected.</p>
          </Card>
          <Card className="p-5">
            <p className="flex items-center gap-2 text-sm text-pw-body">
              <FiHome className="h-4 w-4 text-pw-button" /> Boarding slips
            </p>
            <p className="mt-1 text-2xl font-semibold text-pw-ink">{totals.boardingSlips}</p>
          </Card>
          <Card className="p-5">
            <p className="flex items-center gap-2 text-sm text-pw-body">
              <FiClock className="h-4 w-4 text-pw-button" /> Daycare slips
            </p>
            <p className="mt-1 text-2xl font-semibold text-pw-ink">{totals.daycareSlips}</p>
          </Card>
        </div>
      )}

      {!loading && slips.length === 0 && (
        <Card className="px-6 py-10 text-center">
          <FiFileText className="mx-auto h-8 w-8 text-pw-stroke" />
          <p className="mt-3 text-pw-body">No bills issued yet.</p>
          <p className="mt-1 text-sm text-pw-muted">
            One is written when you complete a boarding stay, and on the 1st of
            each month for every daycare subscription.
          </p>
        </Card>
      )}

      {slips.length > 0 && (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-pw-box text-left text-xs uppercase tracking-wide text-pw-body">
                <tr>
                  <th className="px-5 py-3 font-medium">Slip</th>
                  <th className="px-5 py-3 font-medium">For</th>
                  <th className="px-5 py-3 font-medium">Period</th>
                  <th className="px-5 py-3 text-right font-medium">Qty</th>
                  <th className="px-5 py-3 text-right font-medium">Rate</th>
                  <th className="px-5 py-3 text-right font-medium">Total</th>
                  <th className="px-5 py-3 font-medium">Issued by</th>
                </tr>
              </thead>
              <tbody>
                {pager.shown.map((s) => (
                  <tr key={s.id} className="border-t border-pw-stroke/40">
                    <td className="px-5 py-3">
                      <span className="font-mono text-xs text-pw-ink">{s.slipNo}</span>
                      <span className={`ml-2 rounded-pill px-2 py-0.5 text-[10px] font-medium ${
                        s.kind === "BOARDING" ? "bg-sky-50 text-sky-700" : "bg-pw-input text-pw-button"}`}>
                        {s.kind === "BOARDING" ? "boarding" : "daycare"}
                      </span>
                    </td>
                    <td className="px-5 py-3 text-pw-body">
                      {s.petName}
                      <span className="block text-xs text-pw-muted">{s.ownerName}</span>
                    </td>
                    <td className="px-5 py-3 text-pw-body">
                      {day(s.periodFrom)} – {day(s.periodTo)}
                      {s.slotLabel && <span className="block text-xs text-pw-muted">{s.slotLabel}</span>}
                    </td>
                    <td className="px-5 py-3 text-right text-pw-body">{s.units} {s.unitLabel}</td>
                    <td className="px-5 py-3 text-right text-pw-body">{money(s.unitRate)}</td>
                    <td className="px-5 py-3 text-right font-medium text-pw-ink">{money(s.total)}</td>
                    <td className="px-5 py-3 text-pw-muted">
                      {/* NULL issued_by is the monthly job, not a missing
                          name -- saying "—" would read as data loss. */}
                      {s.issuedByName ?? <span className="italic">automatic</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination {...pager.props} noun="bills" />
        </Card>
      )}
    </StaffLayout>
  );
}
