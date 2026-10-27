import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { FiFileText, FiRefreshCw, FiHome, FiClock } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Pagination from "../../components/ui/Pagination";
import { usePaged } from "../../lib/usePaged";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import SectionTabs from "../../components/portal/SectionTabs";
import { ADOPTER_BOARDING_TABS } from "../../lib/navTabs";

/**
 * Every bill this customer has been given.
 *
 * THERE IS NOTHING TO PAY HERE, AND THAT IS THE DESIGN. PawHome records what
 * is owed and stops -- no gateway, no card, no PAID flag, no balance. The cash
 * is handed over at the shelter counter. So this page has no Pay button, and
 * adding one would mean adding a whole payments subsystem behind it.
 *
 * TWO KINDS OF SLIP, ONE TABLE. A boarding slip bills nights actually stayed;
 * a daycare slip bills one month. ck_slip_source guarantees a slip is exactly
 * one of the two, which is why `kind` can be derived rather than stored.
 */
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "short", day: "numeric" });
};

const money = (n) => `৳${Number(n ?? 0).toLocaleString(undefined,
  { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function MySlipsPage() {
  const [slips, setSlips] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/slips/mine");
      setSlips(data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your bills."));
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
        const { data } = await api.get("/slips/mine");
        if (!cancelled) setSlips(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your bills."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const total = slips.reduce((sum, s) => sum + Number(s.total ?? 0), 0);

  const pager = usePaged(slips, 8);

  return (
    <AdopterLayout
      title="My Bills"
      subtitle="Boarding stays and daycare months. Settled in cash at the shelter."
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

      {slips.length > 0 && (
        <Card tinted className="mb-6 flex flex-wrap items-center justify-between gap-4 px-6 py-5">
          <div>
            <p className="text-sm text-pw-body">{slips.length} bill{slips.length === 1 ? "" : "s"} issued</p>
            <p className="text-2xl font-semibold text-pw-ink">{money(total)}</p>
          </div>
          <p className="max-w-sm text-sm text-pw-muted">
            This is what has been billed, not what is outstanding — PawHome
            does not track payments.
          </p>
        </Card>
      )}

      {!loading && slips.length === 0 && (
        <Card className="px-6 py-10 text-center">
          <FiFileText className="mx-auto h-8 w-8 text-pw-stroke" />
          <p className="mt-3 text-pw-body">No bills yet.</p>
          <p className="mt-1 text-sm text-pw-muted">
            One is written when a boarding stay ends, and on the 1st of each
            month for a daycare subscription.
          </p>
        </Card>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        {pager.shown.map((s) => (
          /* min-w-0 IS NOT DECORATION. A grid track of 1fr has
             min-width:auto, so the column refuses to shrink below the card's
             min-content width -- and the slip number is a font-mono string
             that does not wrap. At 400px that pushed the card 32px past the
             viewport and the whole page scrolled sideways. The inner truncate
             only works once this lets the box get smaller. */
          <Card key={s.id} className="min-w-0 p-5">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <p className="font-mono text-xs text-pw-muted">{s.slipNo}</p>
                <h3 className="mt-1 truncate text-base font-semibold text-pw-ink">
                  {s.petName} — {s.shelterName}
                </h3>
              </div>
              <span className={`shrink-0 inline-flex items-center gap-1.5 rounded-pill px-3 py-1 text-xs font-medium ${
                s.kind === "BOARDING" ? "bg-sky-50 text-sky-700" : "bg-pw-input text-pw-button"}`}>
                {s.kind === "BOARDING"
                  ? <><FiHome className="h-3.5 w-3.5" /> Boarding</>
                  : <><FiClock className="h-3.5 w-3.5" /> Daycare</>}
              </span>
            </div>

            <p className="mt-3 text-sm text-pw-body">
              {day(s.periodFrom)} – {day(s.periodTo)}
              {s.slotLabel && <> &middot; {s.slotLabel}</>}
            </p>

            <div className="mt-4 flex items-end justify-between gap-4">
              <p className="text-sm text-pw-muted">
                {s.units} {s.unitLabel} × {money(s.unitRate)}
              </p>
              <p className="text-xl font-semibold text-pw-ink">{money(s.total)}</p>
            </div>

            <div className="mt-4 flex justify-end">
              <Button to={`/app/slips/${s.id}`} variant="outline" size="sm">
                <FiFileText className="h-4 w-4" /> View slip
              </Button>
            </div>
          </Card>
        ))}
      </div>

      {/* A GRID, NOT A PANEL, so the pager gets its own card and no hairline
          -- there is nothing above it for a rule to separate it from. */}
      {pager.paged && (
        <Card className="mt-5 overflow-hidden">
          <Pagination {...pager.props} bordered={false} noun="bills" />
        </Card>
      )}
    </AdopterLayout>
  );
}
