import { useCallback, useEffect, useState } from "react";
import {
  FiPlus, FiTrash2, FiHome, FiDollarSign, FiRefreshCw,
} from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * A stay in progress: which room the pet is in, and what it has cost so far.
 *
 * TWO THINGS IN ONE DIALOG BECAUSE THEY ARE THE SAME VISIT. A staff member
 * doing the morning round moves a dog out of the kennel being cleaned and
 * writes down that it had its worming tablet. Two buttons on an already
 * crowded card, opening two dialogs, to record one walk down the corridor.
 *
 * NEITHER OF THESE USED TO BE POSSIBLE.
 *
 *   THE ROOM was assigned once, at accept(), and was then the only room that
 *   stay could ever have -- so a kennel that needed cleaning, or two animals
 *   that turned out not to get on, had no answer but ending the stay.
 *
 *   THE EXPENSES could only be typed into the check-out dialog, which made
 *   the bill a feat of memory: a fortnight of feeding and medication, entered
 *   by whoever was on shift, and the person handing the animal back on Friday
 *   was not there on Tuesday.
 *
 * THE TOTALS COME FROM THE SERVER, NEVER FROM ARITHMETIC HERE.
 * boarding_charges.line_total is a stored generated column -- the database
 * computes ROUND(quantity x price, 2) -- so the running total on this screen
 * and the line on the printed slip are the same number rounded once.
 */
const money = (n) => `৳${Number(n || 0).toFixed(2)}`;

const BLANK = { description: "", quantity: "1", unitPrice: "" };

export default function StayManageModal({ open, onClose, stay, onChanged }) {
  const [charges, setCharges] = useState([]);
  const [suites, setSuites] = useState([]);
  const [draft, setDraft] = useState(BLANK);
  const [suiteId, setSuiteId] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    if (!stay) return;
    try {
      const [detailRes, suiteRes] = await Promise.all([
        api.get(`/staff/boarding/${stay.id}`),
        api.get(`/staff/boarding/${stay.id}/suites`),
      ]);
      setCharges(detailRes.data.charges ?? []);
      setSuites(suiteRes.data);
      setSuiteId(String(detailRes.data.request.suiteId ?? ""));
      setError("");
    } catch (err) {
      setError(apiError(err, "Could not load this stay."));
    } finally {
      setLoading(false);
    }
  }, [stay]);

  useEffect(() => {
    if (!open || !stay) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const [detailRes, suiteRes] = await Promise.all([
          api.get(`/staff/boarding/${stay.id}`),
          api.get(`/staff/boarding/${stay.id}/suites`),
        ]);
        if (cancelled) return;
        setCharges(detailRes.data.charges ?? []);
        setSuites(suiteRes.data);
        setSuiteId(String(detailRes.data.request.suiteId ?? ""));
      } catch (err) {
        if (!cancelled) setError(apiError(err, "Could not load this stay."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [open, stay]);

  if (!open || !stay) return null;

  async function run(fn, fallback) {
    setBusy(true);
    setError("");
    try {
      await fn();
      await load();
      onChanged?.();
      return true;
    } catch (err) {
      setError(apiError(err, fallback));
      return false;
    } finally {
      setBusy(false);
    }
  }

  const total = charges.reduce((sum, c) => sum + Number(c.lineTotal || 0), 0);
  const current = suites.find((s) => s.current);
  const moved = suiteId !== "" && String(current?.id ?? "") !== suiteId;

  async function addCharge() {
    if (!draft.description.trim()) { setError("Say what the charge is for."); return; }
    if (!(Number(draft.quantity) > 0)) { setError("A charge needs a quantity above zero."); return; }
    if (!(Number(draft.unitPrice) >= 0)) { setError("A price cannot be negative."); return; }
    const ok = await run(() => api.post(`/staff/boarding/${stay.id}/charges`, {
      description: draft.description.trim(),
      quantity: Number(draft.quantity),
      unitPrice: Number(draft.unitPrice),
    }), "Could not add that charge.");
    if (ok) setDraft(BLANK);
  }

  return (
    <Modal
      open={open}
      onClose={busy ? undefined : onClose}
      title={`${stay.petName}'s stay`}
      subtitle="Move them between rooms, and record what they have cost so far."
      size="lg"
      footer={<Button size="sm" onClick={onClose} disabled={busy}>Done</Button>}
    >
      {error && (
        <p role="alert" className="mb-4 rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
          {error}
        </p>
      )}

      {/* ------------------------------------------------------------- room */}
      <section>
        <h3 className="flex items-center gap-2 text-sm font-semibold text-pw-ink">
          <FiHome className="h-4 w-4 text-pw-button" /> Room
        </h3>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Select className="min-w-[16rem] flex-1" value={suiteId} disabled={busy || loading}
            onChange={(e) => setSuiteId(e.target.value)}>
            <option value="">No room assigned</option>
            {/* `current` is the room this pet is already in, and the server
                excludes this stay from that room's count -- otherwise a
                one-place kennel would show as full because of the animal you
                are trying to move out of it. */}
            {suites.map((s) => (
              <option key={s.id} value={s.id} disabled={s.occupied && !s.current}>
                {s.name}
                {s.species ? ` (${s.species.toLowerCase()} only)` : " (any species)"}
                {` — ${s.placesUsed} of ${s.capacity} taken`}
                {s.current ? " · current" : s.occupied ? " (full)" : ""}
              </option>
            ))}
          </Select>
          <Button size="sm" disabled={busy || !moved || suiteId === ""}
            onClick={() => run(
              () => api.post(`/staff/boarding/${stay.id}/suite`, { suiteId: Number(suiteId) }),
              "Could not move them.")}>
            Move
          </Button>
        </div>
        <p className="mt-2 text-xs text-pw-muted">
          The owner is told when a pet is moved while it is staying, and the
          move is written to the stay&rsquo;s timeline.
        </p>
      </section>

      {/* ---------------------------------------------------------- expenses */}
      <section className="mt-7 border-t border-pw-stroke/40 pt-6">
        <div className="flex items-center justify-between gap-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-pw-ink">
            <FiDollarSign className="h-4 w-4 text-pw-button" /> Expenses so far
          </h3>
          <span className="text-[15px] font-semibold tabular-nums text-pw-ink">
            {money(total)}
          </span>
        </div>

        {loading ? (
          <p className="mt-4 flex items-center gap-2 text-sm text-pw-muted">
            <FiRefreshCw className="h-4 w-4 animate-spin" /> Loading…
          </p>
        ) : charges.length === 0 ? (
          <p className="mt-3 text-sm text-pw-muted">
            Nothing charged yet. Food, medication and vet visits added here go
            onto the bill when the pet is collected.
          </p>
        ) : (
          <ul className="mt-3 divide-y divide-pw-stroke/40">
            {charges.map((c) => (
              <li key={c.id} className="flex items-center gap-3 py-2.5">
                <div className="min-w-0 flex-1">
                  <p className="text-[15px] text-pw-ink">{c.description}</p>
                  <p className="mt-0.5 text-xs text-pw-muted">
                    {Number(c.quantity)} &times; {money(c.unitPrice)}
                    {c.addedByName && <> &middot; {c.addedByName}</>}
                    {c.addedAt && <> &middot; {String(c.addedAt).slice(0, 10)}</>}
                  </p>
                </div>
                <span className="shrink-0 text-[15px] font-medium tabular-nums text-pw-ink">
                  {money(c.lineTotal)}
                </span>
                <Button variant="outline" size="sm" className="!px-2 !border-pw-danger !text-pw-danger"
                  disabled={busy} aria-label={`Remove ${c.description}`}
                  onClick={() => run(
                    () => api.delete(`/staff/boarding/${stay.id}/charges/${c.id}`),
                    "Could not remove that charge.")}>
                  <FiTrash2 className="h-3.5 w-3.5" />
                </Button>
              </li>
            ))}
          </ul>
        )}

        <div className="mt-4 grid gap-2 sm:grid-cols-[1fr_5rem_7rem_auto]">
          <Input label="What for" placeholder="Worming tablet" value={draft.description}
            onChange={(e) => setDraft({ ...draft, description: e.target.value })}
            maxLength={120} />
          <Input label="Qty" type="number" step="0.01" min="0.01" value={draft.quantity}
            onChange={(e) => setDraft({ ...draft, quantity: e.target.value })} />
          <Input label="Price (৳)" type="number" step="0.01" min="0" placeholder="0.00"
            value={draft.unitPrice}
            onChange={(e) => setDraft({ ...draft, unitPrice: e.target.value })} />
          <div className="flex items-end">
            <Button size="sm" disabled={busy} onClick={addCharge}>
              <FiPlus className="h-4 w-4" /> Add
            </Button>
          </div>
        </div>
      </section>
    </Modal>
  );
}
