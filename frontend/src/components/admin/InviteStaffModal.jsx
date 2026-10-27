import { useEffect, useState } from "react";
import { FiInfo } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select } from "../ui/Field";
import api from "../../api/client";

/**
 * Invite a staff member.
 *
 * The shelter is required, and not by choice:
 *
 *   CONSTRAINT ck_users_staff_shelter CHECK (
 *       (role =  'STAFF' AND shelter_id IS NOT NULL) OR
 *       (role <> 'STAFF' AND shelter_id IS     NULL))
 *
 * A staff row with no shelter cannot be written at all, so an invitation that
 * did not name one would fail at the INSERT. The form asks for it up front
 * rather than letting the admin discover that at the end.
 *
 * NO PASSWORD FIELD HERE, deliberately. An admin who types someone else's
 * initial password knows it, and it usually ends up in a chat message. The
 * invitation carries a one-time link and the new staff member sets their own.
 *
 * THE PARENT OWNS THE REQUEST -- the same shape as AddShelterModal. This form
 * hands up a payload and is told `busy` and `error`; it does not know what URL
 * the invitation goes to. Which means the one thing it must NOT do is clear
 * itself on submit: an address the server rejects has to still be on screen
 * for the admin to correct.
 */
export default function InviteStaffModal({
  open, onClose, onInvite, busy = false, error = "",
}) {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [shelter, setShelter] = useState("");
  const [title, setTitle] = useState("");
  const [errors, setErrors] = useState({});

  // Real shelters, not the placeholder array. An admin-side dropdown offering
  // shelters that do not exist in the database is how a form gets filled in
  // and then rejected by fk_users_shelter on submit.
  //
  // INACTIVE ones are left out for the same reason: InviteService refuses
  // them, so offering one is offering a choice that always fails.
  const [shelters, setShelters] = useState([]);
  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    api.get("/admin/shelters")
      .then(({ data }) => {
        if (!cancelled) setShelters(data.filter((x) => x.status !== "INACTIVE"));
      })
      .catch(() => { if (!cancelled) setShelters([]); });
    return () => { cancelled = true; };
  }, [open]);

  function validate() {
    const next = {};
    if (!name.trim()) next.name = "Who is this invitation for?";
    if (!email.trim()) next.email = "An email is required — the invitation goes there.";
    else if (!/^\S+@\S+\.\S+$/.test(email)) next.email = "That doesn't look like an email address.";
    if (!shelter) next.shelter = "Staff must belong to a shelter.";
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function submit(e) {
    e.preventDefault();
    if (busy || !validate()) return;
    // Field names match InviteRequest exactly. Anything else arrives null and
    // trips @NotBlank with a message about a field the admin never saw.
    onInvite?.({
      fullName: name.trim(),
      email: email.trim(),
      shelterId: Number(shelter),
      jobTitle: title.trim() || null,
    });
  }

  function reset() {
    setName("");
    setEmail("");
    setShelter("");
    setTitle("");
    setErrors({});
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Invite Staff"
      subtitle="They set their own password from the link."
      size="md"
    >
      <form onSubmit={submit} noValidate>
        {error && (
          <p role="alert" className="mb-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}

        <Input
          label="Full name"
          placeholder="e.g. Maria Santos"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={errors.name}
        />

        <Input
          label="Email"
          type="email"
          className="mt-5"
          placeholder="maria@shelter.org"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={errors.email}
        />

        <Select
          label="Shelter"
          required
          className="mt-5"
          value={shelter}
          onChange={(e) => setShelter(e.target.value)}
        >
          <option value="">
            {shelters.length ? "Select a shelter…" : "Loading shelters…"}
          </option>
          {shelters.map((s) => (
            <option key={s.id} value={s.id}>{s.name} — {s.location}</option>
          ))}
        </Select>
        {errors.shelter && <p className="mt-1.5 text-sm text-pw-danger">{errors.shelter}</p>}

        <Input
          label="Job title (optional)"
          className="mt-5"
          placeholder="Shelter Manager, Veterinarian, Lead Volunteer…"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
        />

        <div className="mt-6 flex gap-3 rounded-card bg-pw-input p-4">
          <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
          <p className="text-sm leading-relaxed text-pw-body">
            They will see only their own shelter's pets, applications and
            boarding — never the whole network. The link they receive is good
            for seven days.
          </p>
        </div>

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={close} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={busy}>
            {busy ? "Sending…" : "Send invitation"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
