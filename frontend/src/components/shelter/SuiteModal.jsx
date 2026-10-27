import { useState } from "react";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { SPECIES } from "../../lib/species";

/**
 * Add or edit a room. Shared by the staff and admin portals.
 *
 * ONE MODAL, TWO BASE PATHS, and that is the whole reason it moved out of
 * pages/staff/ShelterPage. The two portals reach the same three operations
 * through different prefixes -- staff through /shelters/mine/suites, which
 * takes the shelter from their account, and an admin through
 * /admin/shelters/{id}/suites, which names one. The admin trio had shipped and
 * nothing called it: an administrator could read a shelter's rooms and not
 * change them.
 *
 * COPYING THIS FILE WOULD HAVE BEEN THE SHORTER ROAD AND THE WRONG ONE. The
 * capacity bounds below are ck_suites_capacity said in JavaScript; two copies
 * means the day that constraint moves, one of them still enforces the old
 * range and the error surfaces as a 500 from InnoDB on one portal only.
 *
 * Mounted with key={suite.id} when editing, so the form state seeds from a
 * useState initialiser rather than an effect that copies props into state on
 * every render.
 */
export default function SuiteModal({ open, onClose, suite = null, onSaved,
                                    basePath = "/shelters/mine" }) {
  const editing = suite != null;
  const [name, setName] = useState(suite?.name ?? "");
  const [species, setSpecies] = useState(suite?.species ?? "");
  // V16. A string, not a number, because that is what an <input> holds -- and
  // "" has to stay tellable from 0 so a half-typed field is not read as a
  // room with no places in it.
  const [capacity, setCapacity] = useState(String(suite?.capacity ?? 1));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      // species "" means the suite takes anything, which the column stores as
      // NULL. Sending the empty string would fail to bind to the enum.
      const places = Number(capacity);
      if (!Number.isInteger(places) || places < 1 || places > 500) {
        setError("A room needs between 1 and 500 places.");
        setBusy(false);
        return;
      }
      const body = {
        name: name.trim(), species: species || null, active: true,
        capacity: places,
      };
      const { data } = editing
        ? await api.put(`${basePath}/suites/${suite.id}`, body)
        : await api.post(`${basePath}/suites`, body);
      onSaved(data);
      onClose();
    } catch (err) {
      setError(apiError(err, "That could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={editing ? "Edit room" : "Add a room"}
      subtitle={editing
        ? "Changing a room does not move the animals already booked into it — and a room may be shrunk below the number currently in it, so the record can catch up with the building."
        : "A room appears in the accept dialog as soon as you save it."}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button form="suite-form" type="submit"
                  disabled={busy || !name.trim() || !capacity}>
            {busy ? "Saving…" : editing ? "Save changes" : "Add room"}
          </Button>
        </>
      }
    >
      <form id="suite-form" onSubmit={submit} className="space-y-4">
        <Input
          label="Room name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Kennel 4"
          maxLength={60}
          autoFocus
        />
        <Input
          label="Places"
          type="number"
          min={1}
          max={500}
          value={capacity}
          onChange={(e) => setCapacity(e.target.value)}
          hint="How many animals fit in this room at once. A kennel is usually 1–2; a communal cat loft can be twenty."
        />
        <Select
          label="Takes"
          value={species}
          onChange={(e) => setSpecies(e.target.value)}
        >
          {SPECIES.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </Select>
        <p className="text-sm text-pw-muted">
          A room set to one species is only offered for boarding requests for
          that animal. “Any species” is offered for all of them.
        </p>
        {error && <p className="text-sm text-pw-danger">{error}</p>}
      </form>
    </Modal>
  );
}
