import { useEffect, useState } from "react";
import { FiInfo } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * Generate Report.
 *
 * Four inputs, because that is exactly what a `reports` row needs:
 * report_type, date_from, date_to and a nullable shelter_id. Nothing else is
 * asked for, and nothing the table stores is left to a default.
 *
 * THE FILE IS SERVER WORK, and deliberately so. Building it in the browser
 * would mean shipping every adoption record to the client to summarise them,
 * and the file would exist only in that one download -- no row in `reports`,
 * nothing for another admin to find next week. The endpoint writes the file,
 * inserts the row, and returns the row.
 *
 * IT USED TO CALL NOTHING. onGenerate handed the form values back to the page,
 * which showed "Report queued — it will appear in the library" and did not.
 * The request below is that promise being kept.
 *
 * THE TYPE LIST COMES FROM THE SERVER, not from a constant here. The four
 * values are a database ENUM; a second copy in the frontend is one rename away
 * from offering a type the column will reject.
 */
export default function GenerateReportModal({ open, onClose, onGenerated, types = [] }) {
  const [type, setType] = useState("ADOPTION_SUMMARY");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [shelter, setShelter] = useState("");
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState("");

  // Real shelters, not a placeholder array. An admin-side dropdown offering
  // shelters that do not exist is how a form gets filled in and then rejected
  // on submit.
  const [shelters, setShelters] = useState([]);
  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    api.get("/admin/shelters")
      .then(({ data }) => { if (!cancelled) setShelters(data); })
      .catch(() => { if (!cancelled) setShelters([]); });
    return () => { cancelled = true; };
  }, [open]);

  const chosen = types.find((t) => t.value === type);
  // PET_INVENTORY is a snapshot of who is in the shelters now, so its rows do
  // not depend on the window. Saying so beside the date fields is better than
  // letting somebody pick a careful range and wonder why it changed nothing.
  const snapshot = type === "PET_INVENTORY";

  function validate() {
    const next = {};
    if (!from) next.from = "Pick the start of the window.";
    if (!to) next.to = "Pick the end of the window.";
    // An inverted range would otherwise return an empty report rather than an
    // error, which is the confusing failure -- a valid file with nothing in it.
    if (from && to && to < from) next.to = "The end date is before the start date.";
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  async function submit(e) {
    e.preventDefault();
    if (!validate()) return;
    setBusy(true);
    setFailed("");
    try {
      const { data } = await api.post("/admin/reports", {
        type,
        from,
        to,
        shelterId: shelter ? Number(shelter) : null, // null = all shelters
      });
      onGenerated?.(data);
      reset();
    } catch (err) {
      setFailed(apiError(err, "That report could not be generated."));
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setType("ADOPTION_SUMMARY");
    setFrom("");
    setTo("");
    setShelter("");
    setErrors({});
    setFailed("");
  }

  function close() {
    reset();
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Generate Report"
      subtitle="Saved to the library and downloadable as a CSV."
      size="md"
    >
      <form onSubmit={submit} noValidate>
        <Select label="Report type" value={type} onChange={(e) => setType(e.target.value)}>
          {types.map((t) => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </Select>
        {chosen && (
          <p className="mt-2 text-sm leading-relaxed text-pw-muted">{chosen.blurb}</p>
        )}

        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <Input
            label="From"
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            error={errors.from}
          />
          <Input
            label="To"
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            error={errors.to}
          />
        </div>
        {snapshot && (
          <p className="mt-2 text-sm text-pw-muted">
            Recorded on the report, but not applied — a pet inventory describes
            today.
          </p>
        )}

        <Select
          label="Shelter"
          className="mt-5"
          value={shelter}
          onChange={(e) => setShelter(e.target.value)}
        >
          <option value="">All shelters</option>
          {shelters.map((s) => (
            <option key={s.id} value={s.id}>{s.name}</option>
          ))}
        </Select>

        <div className="mt-6 flex gap-3 rounded-card bg-pw-input p-4">
          <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
          <p className="text-sm leading-relaxed text-pw-body">
            The file is built on the server and kept in the library, so anyone
            on the admin team can find it later — not just whoever clicked
            Generate. Reports can hold adopters’ names and emails, so they
            download only for signed-in admins.
          </p>
        </div>

        {failed && <p className="mt-4 text-sm text-pw-danger">{failed}</p>}

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={close} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={busy}>{busy ? "Building…" : "Generate"}</Button>
        </div>
      </form>
    </Modal>
  );
}
