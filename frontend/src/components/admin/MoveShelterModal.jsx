import { useEffect, useState } from "react";
import { FiAlertTriangle } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { Select } from "../ui/Field";
import api from "../../api/client";

/**
 * Move a staff member to another shelter.
 *
 * SAY WHAT THIS ACTUALLY DOES. It reads like an administrative correction and
 * it is an access-control change: ScopeGuard reads shelter_id off the user row
 * on every request, so the person stops seeing their old shelter's pets,
 * applications and boarding on their very next click -- no sign-out, no delay.
 * An admin about to press this should not have to know that to predict it.
 *
 * The parent owns the request, the same as every other modal here: this form
 * hands up a shelter id and is told `busy` and `error`.
 */
export default function MoveShelterModal({ user, onClose, onMove, busy = false, error = "" }) {
  const [shelterId, setShelterId] = useState(String(user?.shelterId ?? ""));
  const [shelters, setShelters] = useState([]);
  const [touched, setTouched] = useState(false);

  // INACTIVE shelters are left out: the server refuses them, so offering one
  // is offering a choice that always fails.
  useEffect(() => {
    let cancelled = false;
    api.get("/admin/shelters")
      .then(({ data }) => {
        if (!cancelled) setShelters(data.filter((s) => s.status !== "INACTIVE"));
      })
      .catch(() => { if (!cancelled) setShelters([]); });
    return () => { cancelled = true; };
  }, []);

  const unchanged = shelterId === String(user?.shelterId ?? "");

  function submit(e) {
    e.preventDefault();
    setTouched(true);
    if (busy || !shelterId || unchanged) return;
    onMove?.(Number(shelterId));
  }

  return (
    <Modal
      open
      onClose={busy ? () => {} : onClose}
      title="Move to another shelter"
      subtitle={user ? `${user.fullName} · currently ${user.shelterName ?? "no shelter"}` : undefined}
      size="md"
    >
      <form onSubmit={submit} noValidate>
        {error && (
          <p role="alert" className="mb-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}

        <Select
          label="New shelter"
          required
          value={shelterId}
          onChange={(e) => setShelterId(e.target.value)}
        >
          <option value="">
            {shelters.length ? "Select a shelter…" : "Loading shelters…"}
          </option>
          {shelters.map((s) => (
            <option key={s.id} value={s.id}>{s.name} — {s.location}</option>
          ))}
        </Select>
        {touched && !shelterId && (
          <p className="mt-1.5 text-sm text-pw-danger">Choose the shelter they are moving to.</p>
        )}
        {touched && shelterId && unchanged && (
          <p className="mt-1.5 text-sm text-pw-muted">
            That is the shelter they are already in.
          </p>
        )}

        <div className="mt-6 flex gap-3 rounded-card bg-amber-50 p-4">
          <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-700" />
          <p className="text-sm leading-relaxed text-amber-900">
            This takes effect immediately, without them signing out. They lose
            access to their old shelter's pets, applications and boarding, and
            gain access to the new one's.
          </p>
        </div>

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={busy || unchanged}>
            {busy ? "Moving…" : "Move staff member"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
