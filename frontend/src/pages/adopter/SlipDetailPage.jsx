import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import { FiArrowLeft, FiPrinter } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Logo from "../../components/brand/Logo";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * One slip, laid out to be printed.
 *
 * THE SLIP IS A DOCUMENT, NOT A DASHBOARD. Everything on this page comes from
 * the billing_slips row and nothing is recomputed from today's prices --
 * units, unit_rate and total are stored precisely so the paper still says what
 * it said on the day it was handed over, even after the shelter changes what
 * it charges. Joining the live rate here would quietly rewrite history every
 * time somebody reopened an old bill.
 *
 * NO PAY BUTTON, ON PURPOSE. Payment happens in cash at the counter and this
 * system does not model it. A slip records what is owed and stops.
 *
 * PRINTING IS window.print() AND A print: STYLESHEET, not a generated PDF. The
 * browser already knows how to make a PDF of a page; a server-side renderer
 * would be a second layout to keep in step with this one, and they always
 * drift.
 */
const day = (iso) => {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { year: "numeric", month: "long", day: "numeric" });
};

const money = (n) => `৳${Number(n ?? 0).toLocaleString(undefined,
  { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function SlipDetailPage() {
  const { id } = useParams();
  const [slip, setSlip] = useState(null);
  const [items, setItems] = useState([]);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        // THE SHAPE CHANGED IN V15: this endpoint returns { slip, items }
        // rather than the slip alone, because a bill can now carry extra
        // charges and a list of bills must not fetch every line of every one.
        const { data } = await api.get(`/slips/${id}`);
        if (cancelled) return;
        setSlip(data.slip);
        setItems(data.items ?? []);
      } catch (err) {
        if (!cancelled) setError(apiError(err, "Could not load that slip."));
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  return (
    <AdopterLayout title="Payment Slip" subtitle={slip?.slipNo ?? ""}>
      {/* Hides the portal chrome when printed, so the sheet is the slip and
          nothing else. Scoped to this page rather than index.css: no other
          page is meant to be printed. */}
      <style>{`
        @media print {
          body { background: #fff; }
          aside, header, nav, .no-print { display: none !important; }
          main { padding: 0 !important; }
          .slip-sheet { border: none !important; box-shadow: none !important; }
        }
      `}</style>

      {/* Same max-width as the sheet below, so Back and Print sit on its
          edges instead of floating at the far sides of the page. */}
      <div className="no-print mx-auto mb-5 flex max-w-2xl items-center justify-between gap-4">
        <Link to="/app/slips"
              className="inline-flex items-center gap-2 text-sm font-medium text-pw-button hover:underline">
          <FiArrowLeft className="h-4 w-4" /> All bills
        </Link>
        {slip && (
          <Button variant="outline" size="sm" onClick={() => window.print()}>
            <FiPrinter className="h-4 w-4" /> Print
          </Button>
        )}
      </div>

      {error && (
        <p role="alert" className="rounded-card bg-rose-50 px-5 py-4 text-sm text-pw-danger">
          {error}
        </p>
      )}

      {slip && (
        <Card className="slip-sheet mx-auto max-w-2xl p-8 sm:p-10">
          <div className="flex items-start justify-between gap-6 border-b border-pw-stroke/50 pb-6">
            {/* to={null}: a slip is printed, and a wordmark that navigates
                away is a dead end on paper and a stray link on screen. */}
            <Logo to={null} />
            <div className="text-right">
              <p className="text-xs uppercase tracking-wide text-pw-muted">Payment slip</p>
              <p className="font-mono text-sm font-semibold text-pw-ink">{slip.slipNo}</p>
              <p className="mt-1 text-xs text-pw-muted">
                Issued {new Date(slip.issuedAt).toLocaleDateString(undefined,
                  { year: "numeric", month: "long", day: "numeric" })}
              </p>
            </div>
          </div>

          <div className="grid gap-6 border-b border-pw-stroke/50 py-6 sm:grid-cols-2">
            <div>
              <p className="text-xs uppercase tracking-wide text-pw-muted">Billed to</p>
              <p className="mt-1 font-medium text-pw-ink">{slip.ownerName}</p>
              <p className="text-sm text-pw-body">{slip.ownerEmail}</p>
              {slip.petName && (
                <p className="mt-2 text-sm text-pw-body">For {slip.petName}</p>
              )}
            </div>
            <div className="sm:text-right">
              <p className="text-xs uppercase tracking-wide text-pw-muted">From</p>
              <p className="mt-1 font-medium text-pw-ink">{slip.shelterName}</p>
              <p className="text-sm text-pw-body">{slip.shelterAddress}</p>
              <p className="text-sm text-pw-body">{slip.shelterPhone}</p>
            </div>
          </div>

          <table className="w-full border-b border-pw-stroke/50 py-6 text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-pw-muted">
                <th className="py-3 font-medium">Description</th>
                <th className="py-3 text-right font-medium">Qty</th>
                <th className="py-3 text-right font-medium">Rate</th>
                <th className="py-3 text-right font-medium">Amount</th>
              </tr>
            </thead>
            <tbody>
              {/* The base charge: the nights, or the month. It is not one of
                  the item rows -- see BillingSlipItem for why. */}
              <tr className="border-t border-pw-stroke/40">
                <td className="py-4 pr-4 align-top">
                  <p className="font-medium text-pw-ink">
                    {slip.kind === "BOARDING" ? "Pet boarding" : "Daycare subscription"}
                  </p>
                  <p className="mt-0.5 text-pw-body">
                    {day(slip.periodFrom)} – {day(slip.periodTo)}
                    {slip.slotLabel && <> &middot; {slip.slotLabel}</>}
                  </p>
                </td>
                <td className="py-4 text-right align-top text-pw-body">
                  {slip.units} {slip.unitLabel}
                </td>
                <td className="py-4 text-right align-top text-pw-body">{money(slip.unitRate)}</td>
                <td className="py-4 text-right align-top font-medium text-pw-ink">
                  {money(slip.baseTotal ?? slip.total)}
                </td>
              </tr>

              {/* Food, medication, grooming -- whatever was added at the
                  counter. lineTotal comes from the database, which computes
                  it, so it cannot disagree with the two numbers beside it. */}
              {items.map((it) => (
                <tr key={it.id} className="border-t border-pw-stroke/40">
                  <td className="py-3 pr-4 align-top text-pw-body">{it.description}</td>
                  <td className="py-3 text-right align-top text-pw-body">{Number(it.quantity)}</td>
                  <td className="py-3 text-right align-top text-pw-body">{money(it.unitPrice)}</td>
                  <td className="py-3 text-right align-top text-pw-ink">{money(it.lineTotal)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="flex items-baseline justify-between gap-4 pt-6">
            <span className="text-sm font-medium uppercase tracking-wide text-pw-muted">
              Total due
            </span>
            <span className="text-2xl font-semibold text-pw-ink">{money(slip.total)}</span>
          </div>

          <p className="mt-6 rounded-card bg-pw-box px-5 py-4 text-sm text-pw-body">
            Payable in cash at {slip.shelterName}. PawHome issues the slip; it
            does not take payment or record whether one has been made.
          </p>

          <p className="mt-4 text-xs text-pw-muted">
            {slip.issuedByName
              ? `Issued by ${slip.issuedByName}.`
              : "Issued automatically on the first of the month."}
          </p>
        </Card>
      )}
    </AdopterLayout>
  );
}
