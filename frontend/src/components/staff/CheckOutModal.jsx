import { useEffect, useState } from "react";
import { FiPlus, FiTrash2, FiLogOut } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import api from "../../api/client";

/**
 * Handing a pet back, and writing the bill.
 *
 * THIS IS THE LAST MOMENT EXTRAS CAN BE ADDED, NO LONGER THE ONLY ONE. V17
 * gave staff somewhere to record an expense on the day it happened, so the
 * fortnight of feeding and medication is already listed above -- this dialog is
 * for what gets noticed while the owner is standing at the counter.
 *
 * IT IS STILL THE LAST MOMENT, and that has not softened. There is no endpoint
 * that edits a slip afterwards, deliberately: a slip is a document that was
 * handed over, and one that can be amended later is one a customer cannot
 * trust. So everything the shelter is charging for has to be on this screen
 * before the button is pressed -- which is exactly why the accrued charges are
 * fetched and shown rather than left implied.
 *
 * THE ACCRUED LINES ARE READ-ONLY HERE. They are corrected in the stay dialog,
 * where there is room to see who added each one and when. Two places to edit
 * one list is how the two disagree.
 *
 * EARLY PICKUP NEEDS NO SWITCH. The bill is start_date -> today, so collecting
 * on the third morning of a booked five bills three nights. The line at the top
 * says so, because "why is this cheaper than my quote" is the question this
 * modal exists to answer before it is asked.
 *
 * THE LINE TOTAL IS SHOWN BUT NEVER SENT. billing_slip_items.line_total is a
 * generated column -- the database computes quantity x price -- so posting one
 * would be offering the server a number it is going to ignore.
 */
const money = (n) => `৳${Number(n || 0).toFixed(2)}`;

const BLANK = { description: "", quantity: "1", unitPrice: "" };

export default function CheckOutModal({ open, onClose, stay, busy, onConfirm }) {
  const [lines, setLines] = useState([]);
  const [accrued, setAccrued] = useState([]);
  const [error, setError] = useState("");

  // WHAT HAS ALREADY BEEN CHARGED. Fetched rather than passed down: the board
  // holds BoardingRow, which has no charges on it, and giving every card its
  // charges would be a query per card to draw a list nobody has opened.
  useEffect(() => {
    if (!open || !stay) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/staff/boarding/${stay.id}`);
        if (!cancelled) setAccrued(data.charges ?? []);
      } catch {
        // NOT AN ERROR THE STAFF MEMBER NEEDS. The server adds these to the
        // slip whether or not this fetch succeeded, so a failure here costs a
        // preview, not a charge.
        if (!cancelled) setAccrued([]);
      }
    })();
    return () => { cancelled = true; };
  }, [open, stay]);

  if (!open || !stay) return null;

  const set = (i, key) => (e) =>
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, [key]: e.target.value } : l)));

  const extras = lines.reduce(
    (sum, l) => sum + (Number(l.quantity) || 0) * (Number(l.unitPrice) || 0), 0);

  // The database's own line totals, not multiplied again here -- see the note
  // at the top of StayManageModal on why that matters to the paisa.
  const accruedTotal = accrued.reduce((sum, c) => sum + Number(c.lineTotal || 0), 0);

  // The nights actually stayed, which is what the slip will bill -- not the
  // nights that were booked.
  const today = new Date();
  const start = stay.startDate ? new Date(...stay.startDate.split("-").map((v, i) => i === 1 ? v - 1 : +v)) : null;
  const nights = start
    ? Math.max(1, Math.round((today - start) / 86400000)) : stay.nights;
  const base = nights * Number(stay.dailyRate || 0);

  const submit = () => {
    const filled = lines.filter((l) => l.description.trim() !== "");
    for (const l of filled) {
      if (!(Number(l.quantity) > 0)) { setError("Every charge needs a quantity above zero."); return; }
      if (!(Number(l.unitPrice) >= 0)) { setError("A price cannot be negative."); return; }
    }
    setError("");
    onConfirm(filled.map((l) => ({
      description: l.description.trim(),
      quantity: Number(l.quantity),
      unitPrice: Number(l.unitPrice),
    })));
  };

  return (
    <Modal
      open={open}
      onClose={busy ? undefined : onClose}
      title={`Check out ${stay.petName}`}
      subtitle="The bill is written now, and cannot be changed afterwards."
      size="lg"
      footer={
        <div className="flex justify-end gap-3">
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button onClick={submit} disabled={busy}>
            <FiLogOut className="h-4 w-4" />
            {busy ? "Checking out…" : `Check out and bill ${money(base + accruedTotal + extras)}`}
          </Button>
        </div>
      }
    >
      <div className="space-y-5">
        <div className="rounded-card bg-pw-box px-5 py-4">
          <div className="flex items-baseline justify-between gap-4">
            <span className="text-sm font-medium text-pw-ink">
              {nights} night{nights === 1 ? "" : "s"} × {money(stay.dailyRate)}
            </span>
            <span className="text-[15px] font-semibold text-pw-ink">{money(base)}</span>
          </div>
          <p className="mt-1 text-sm text-pw-muted">
            {stay.startDate} to today
            {stay.endDate && nights < stay.nights && (
              <> — collected early, so {stay.nights - nights} booked night
                {stay.nights - nights === 1 ? " is" : "s are"} not charged.</>
            )}
          </p>
        </div>

        {accrued.length > 0 && (
          <div className="rounded-card border border-pw-stroke/50 px-5 py-4">
            <div className="mb-2 flex items-baseline justify-between gap-4">
              <p className="text-sm font-medium text-pw-ink">
                Charged during the stay
              </p>
              <span className="text-[15px] font-semibold tabular-nums text-pw-ink">
                {money(accruedTotal)}
              </span>
            </div>
            <ul className="divide-y divide-pw-stroke/30">
              {accrued.map((c) => (
                <li key={c.id} className="flex items-center justify-between gap-4 py-1.5">
                  <span className="min-w-0 truncate text-sm text-pw-body">
                    {c.description}
                    <span className="text-pw-muted"> · {Number(c.quantity)} × {money(c.unitPrice)}</span>
                  </span>
                  <span className="shrink-0 text-sm tabular-nums text-pw-ink">
                    {money(c.lineTotal)}
                  </span>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-pw-muted">
              Already on the bill. Correct these from the stay&rsquo;s own dialog.
            </p>
          </div>
        )}

        <div>
          <div className="mb-2 flex items-center justify-between gap-4">
            <p className="text-sm font-medium text-pw-ink">Anything else to charge for?</p>
            <Button variant="ghost" size="sm" onClick={() => setLines((l) => [...l, { ...BLANK }])}>
              <FiPlus className="h-4 w-4" /> Add a charge
            </Button>
          </div>

          {lines.length === 0 && (
            <p className="rounded-card border border-dashed border-pw-stroke/60 px-5 py-4 text-sm text-pw-muted">
              Nothing extra — just the nights. Add a line for medication, extra
              food or grooming.
            </p>
          )}

          <ul className="space-y-3">
            {lines.map((l, i) => (
              <li key={i} className="flex flex-wrap items-end gap-3 sm:flex-nowrap">
                <Input
                  className="min-w-0 flex-1"
                  label={i === 0 ? "What for" : undefined}
                  placeholder="Antibiotics, twice daily"
                  value={l.description}
                  onChange={set(i, "description")}
                />
                <Input
                  className="w-20 shrink-0"
                  label={i === 0 ? "Qty" : undefined}
                  type="number" min="0.01" step="0.01"
                  value={l.quantity}
                  onChange={set(i, "quantity")}
                />
                <Input
                  className="w-28 shrink-0"
                  label={i === 0 ? "Price each" : undefined}
                  type="number" min="0" step="0.01" placeholder="0.00"
                  value={l.unitPrice}
                  onChange={set(i, "unitPrice")}
                />
                <div className="flex w-24 shrink-0 items-center justify-end gap-2 pb-2.5">
                  <span className="text-sm font-medium text-pw-ink">
                    {money((Number(l.quantity) || 0) * (Number(l.unitPrice) || 0))}
                  </span>
                  <button type="button" aria-label="Remove this charge"
                    className="text-pw-muted hover:text-pw-danger"
                    onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}>
                    <FiTrash2 className="h-4 w-4" />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <div className="flex items-baseline justify-between gap-4 border-t border-pw-stroke/50 pt-4">
          <span className="text-sm font-medium uppercase tracking-wide text-pw-muted">Total</span>
          <span className="text-xl font-semibold text-pw-ink">{money(base + accruedTotal + extras)}</span>
        </div>

        {error && (
          <p role="alert" className="rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">{error}</p>
        )}
      </div>
    </Modal>
  );
}
