import { useState } from "react";
import { FiAlertTriangle } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select, Textarea, FileDrop } from "../ui/Field";

const EMPTY = {
  name: "", email: "", phone: "", addressLine: "", city: "", state: "", zip: "",
  capacity: "", status: "ACTIVE", description: "", boardingRateDaily: "",
  // V13. Empty means "not running daycare" -- see the submit handler.
  daycareMonthlyPrice: "", daycareSlotCapacity: "",
};

/**
 * Add or edit a shelter.
 *
 * THIS MODAL NO LONGER RESETS ITSELF ON SUBMIT. It used to clear the form the
 * moment the button was pressed, which was fine while onSave was a local
 * setState and is not fine now that it is a POST: the request fails, the modal
 * stays open, and everything the admin typed is gone. The parent owns the
 * async call, passes `busy` and `error` back, and closing is what clears it.
 *
 * Field names match the API exactly (addressLine, not address). Renaming in
 * the submit handler is where a field quietly stops being sent.
 */
export default function AddShelterModal({
  open, onClose, onSave, busy = false, error = "", shelter = null,
}) {
  const editing = shelter != null;

  // NO EFFECT SYNCING PROPS INTO STATE. The parent renders this component only
  // while it is open, so it mounts fresh every time and the initialiser below
  // runs exactly once per opening -- which is also what stops the edit form
  // showing the PREVIOUS shelter's values, the same bug the pet editor had.
  const [form, setForm] = useState(() => (shelter
    ? {
        name: shelter.name ?? "",
        email: shelter.email ?? "",
        phone: shelter.phone ?? "",
        addressLine: shelter.addressLine ?? "",
        city: shelter.city ?? "",
        state: shelter.state ?? "",
        zip: shelter.zip ?? "",
        capacity: String(shelter.capacity ?? ""),
        status: shelter.status ?? "ACTIVE",
        description: shelter.description ?? "",
        boardingRateDaily: shelter.boardingRateDaily != null
          ? String(shelter.boardingRateDaily) : "",
        daycareMonthlyPrice: shelter.daycareMonthlyPrice != null
          ? String(shelter.daycareMonthlyPrice) : "",
        daycareSlotCapacity: shelter.daycareSlotCapacity != null
          ? String(shelter.daycareSlotCapacity) : "",
      }
    : EMPTY));
  const [logo, setLogo] = useState(null);
  const [errors, setErrors] = useState({});

  const set = (k) => (e) =>
    setForm((f) => ({ ...f, [k]: e?.target ? e.target.value : e }));

  function validate() {
    const next = {};
    if (!form.name.trim()) next.name = "Shelter name is required.";
    // Shape check only. Whether the address actually receives mail is the
    // server's problem, not a regex's -- this catches the typo, not the lie.
    if (!form.email.trim()) next.email = "An email is required.";
    else if (!/^\S+@\S+\.\S+$/.test(form.email)) next.email = "That doesn't look like an email address.";
    if (!form.phone.trim()) next.phone = "A phone number is required.";
    if (!form.addressLine.trim()) next.addressLine = "A street address is required.";
    if (!form.city.trim()) next.city = "Required.";
    if (!form.state.trim()) next.state = "Required.";
    if (!form.zip.trim()) next.zip = "Required.";
    // ck_shelters_capacity says > 0. Catching it here means the admin sees it
    // on the field rather than as a constraint violation from the server.
    if (form.capacity === "" || Number(form.capacity) < 1) {
      next.capacity = "Capacity must be at least 1.";
    }
    if (form.boardingRateDaily !== "" && Number(form.boardingRateDaily) < 0) {
      next.boardingRateDaily = "A daily rate cannot be negative.";
    }
    // ck_shelters_daycare_price and ck_shelters_daycare_capacity say >= 0.
    // Caught here so the admin sees it on the field rather than as a
    // constraint violation from the server.
    if (form.daycareMonthlyPrice !== "" && Number(form.daycareMonthlyPrice) < 0) {
      next.daycareMonthlyPrice = "A monthly price cannot be negative.";
    }
    if (form.daycareSlotCapacity !== "" && Number(form.daycareSlotCapacity) < 0) {
      next.daycareSlotCapacity = "A slot capacity cannot be negative.";
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function submit(e) {
    e.preventDefault();
    if (!validate()) return;
    onSave?.({
      name: form.name.trim(),
      email: form.email.trim(),
      phone: form.phone.trim(),
      addressLine: form.addressLine.trim(),
      city: form.city.trim(),
      state: form.state.trim(),
      zip: form.zip.trim(),
      capacity: Number(form.capacity),
      // Only sent on create -- status has its own endpoint, so that an admin
      // editing a phone number cannot deactivate a shelter with a stale form.
      ...(editing ? {} : { status: form.status }),
      description: form.description.trim(),
      boardingRateDaily: form.boardingRateDaily === ""
        ? 0 : Number(form.boardingRateDaily),
      // Zero, not null: a blank price is a shelter that does not run daycare,
      // and that is exactly the value the offers list filters out on. The
      // capacity falls back to the column's own default rather than to zero,
      // which would read as "we run daycare with no places".
      daycareMonthlyPrice: form.daycareMonthlyPrice === ""
        ? 0 : Number(form.daycareMonthlyPrice),
      daycareSlotCapacity: form.daycareSlotCapacity === ""
        ? 20 : Number(form.daycareSlotCapacity),
    // The file is handed up SEPARATELY rather than folded into the payload,
    // because it cannot travel in a JSON body. The caller creates or updates
    // the shelter first and then posts the image to its own endpoint, which is
    // also why a shelter can exist without a logo and gain one later.
    }, logo?.[0] ?? null);
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={editing ? `Edit ${shelter.name}` : "Add Shelter"}
      size="lg"
    >
      <form onSubmit={submit} noValidate>
        {error && (
          <p className="mb-5 rounded-card bg-pw-input px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}

        <div className="grid gap-4 sm:grid-cols-2">
          <Input
            label="Shelter Name"
            placeholder="e.g. Happy Paws Sanctuary"
            value={form.name}
            onChange={set("name")}
            error={errors.name}
          />
          {/* compact, so it is the same height as the name field beside it */}
          <FileDrop
            compact
            label="Shelter Logo"
            accept="image/*"
            value={logo}
            onChange={setLogo}
            hint="PNG, JPG or WebP"
          />
        </div>

        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <Input
            label="Email"
            type="email"
            placeholder="contact@shelter.com"
            value={form.email}
            onChange={set("email")}
            error={errors.email}
          />
          <Input
            label="Phone"
            type="tel"
            placeholder="(555) 000-0000"
            value={form.phone}
            onChange={set("phone")}
            error={errors.phone}
          />
        </div>

        <Input
          label="Address"
          className="mt-5"
          placeholder="Street Address"
          value={form.addressLine}
          onChange={set("addressLine")}
          error={errors.addressLine}
        />

        <div className="mt-5 grid gap-4 sm:grid-cols-3">
          <Input label="City" value={form.city} onChange={set("city")} error={errors.city} />
          <Input label="State" value={form.state} onChange={set("state")} error={errors.state} />
          <Input label="Zip" value={form.zip} onChange={set("zip")} error={errors.zip} />
        </div>

        <div className="mt-5 grid gap-4 sm:grid-cols-3">
          <Input
            label="Capacity"
            type="number"
            min="1"
            placeholder="50"
            value={form.capacity}
            onChange={set("capacity")}
            error={errors.capacity}
          />
          <Input
            label="Boarding rate / night (৳)"
            type="number"
            min="0"
            step="0.01"
            placeholder="25.00"
            value={form.boardingRateDaily}
            onChange={set("boardingRateDaily")}
            error={errors.boardingRateDaily}
          />
          {/* Only on create. Changing an existing shelter's status is its own
              action, with its own confirmation. */}
          {!editing && (
            <Select label="Status" value={form.status} onChange={set("status")}>
              <option value="ACTIVE">Active</option>
              <option value="PENDING">Pending</option>
              <option value="INACTIVE">Inactive</option>
            </Select>
          )}
        </div>

        {/* ---------------------------------------------------- daycare, V13

            ITS OWN BLOCK, not two more boxes in the row above. "Capacity"
            up there is kennel places for resident animals; the capacity here
            is how many pets may hold the SAME daily band. Two fields called
            capacity, side by side, meaning different things, is how one of
            the two numbers ends up wrong. */}
        <fieldset className="mt-6 rounded-card border border-pw-stroke/50 px-5 py-4">
          <legend className="px-2 text-sm font-medium text-pw-ink">Daycare</legend>
          <p className="mb-4 text-sm text-pw-muted">
            Three fixed bands a day — 09:00, 12:00 and 15:00. Leave the price
            empty if this shelter does not offer daycare.
          </p>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input
              label="Price per band / month (৳)"
              type="number"
              min="0"
              step="0.01"
              placeholder="1500.00"
              value={form.daycareMonthlyPrice}
              onChange={set("daycareMonthlyPrice")}
              error={errors.daycareMonthlyPrice}
            />
            <Input
              label="Pets per band"
              type="number"
              min="0"
              placeholder="20"
              value={form.daycareSlotCapacity}
              onChange={set("daycareSlotCapacity")}
              error={errors.daycareSlotCapacity}
              hint="Not the kennel capacity above — a daycare pet goes home the same day."
            />
          </div>

          {/* PLACES SOLD vs PLACES THE BUILDING HAS. The two are set on
              different screens by different people -- an admin types this
              number, staff build the rooms on My Shelter -- so nothing keeps
              them in step and a shelter can promise more than it can house.

              A NOTE, NOT A VALIDATION ERROR. The rooms are the shelter's to
              build and one may be added tomorrow, so refusing the save would
              lock an admin out over a number that is about to be true. The
              refusal that bites is on the server, at the moment a subscription
              would actually be sold a place the building cannot deliver --
              DaycareService.requireRoomFor. */}
          {editing && Number(form.daycareSlotCapacity) > (shelter?.suitePlaces ?? 0)
            && Number(form.daycareMonthlyPrice) > 0 && (
            <p className="mt-3 flex items-start gap-2 rounded-card bg-amber-50 px-4 py-3 text-sm text-amber-800">
              <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>
                This sells {form.daycareSlotCapacity} places in a band, but
                {" "}{shelter.name}&rsquo;s rooms hold {shelter.suitePlaces}.
                Subscriptions beyond what the rooms can house will be refused
                — add rooms, or raise their capacity, on the shelter&rsquo;s own page.
              </span>
            </p>
          )}
        </fieldset>

        <Textarea
          label="Description"
          className="mt-5"
          placeholder="Tell us about this shelter…"
          value={form.description}
          onChange={set("description")}
        />

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" disabled={busy}>
            {busy ? "Saving…" : editing ? "Save changes" : "Add Shelter"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
