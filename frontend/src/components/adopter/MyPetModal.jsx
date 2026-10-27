import { useState } from "react";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import Checkbox from "../ui/Checkbox";
import { Select, Textarea } from "../ui/Field";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * Add or edit a pet you own.
 *
 * SHORTER THAN THE STAFF'S AddPetModal, and the missing fields are the point.
 * There is no shelter, because no shelter has this animal; no status, because
 * a household pet is OWNED and nothing else; no adoption fee, because nobody
 * is offering it; and no trait chips, because those feed the adoption matching
 * engine and have nothing to say about a pet that is not up for adoption. Four
 * absences, four reasons -- see PetDtos.MyPetRequest, which refuses all four.
 */
const EMPTY = {
  name: "", species: "DOG", breed: "", ageValue: "", ageUnit: "YEARS",
  sex: "MALE", size: "MEDIUM", weightKg: "", description: "",
  neutered: false, microchipped: false,
};

/** The pet as form fields. Numbers become strings; a null becomes "". */
function fromPet(pet) {
  if (!pet) return EMPTY;
  return {
    name: pet.name ?? "",
    species: pet.species ?? "DOG",
    breed: pet.breed ?? "",
    ageValue: pet.ageValue == null ? "" : String(pet.ageValue),
    ageUnit: pet.ageUnit ?? "YEARS",
    sex: pet.sex ?? "MALE",
    size: pet.size ?? "MEDIUM",
    weightKg: pet.weightKg == null ? "" : String(pet.weightKg),
    description: pet.description ?? "",
    neutered: !!pet.neutered,
    microchipped: !!pet.microchipped,
  };
}

/**
 * MOUNTED FRESH EACH TIME, which is why there is no effect refilling the form.
 * The caller renders this only while the dialog is open and gives it a key of
 * the pet's id, so opening it on a different animal is a remount and the
 * initialiser below runs again. The effect version of the same thing sets
 * state synchronously inside an effect body -- the one thing this codebase's
 * lint rule forbids -- and it would also clobber half-typed input whenever a
 * background reload handed back an equal-but-new pet object.
 */
export default function MyPetModal({ open, onClose, pet, onSaved }) {
  const editing = pet != null;
  const [form, setForm] = useState(() => fromPet(pet));
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState("");

  const set = (k) => (e) =>
    setForm((f) => ({ ...f, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value }));

  function validate() {
    const next = {};
    if (!form.name.trim()) next.name = "Give your pet a name.";
    if (!form.breed.trim()) next.breed = 'Write the breed, or "Mixed".';
    // ck_pets_age says age_value > 0, and the DTO caps it at 900 to catch a
    // date typed into an age box. Both said here so the message lands on the
    // field rather than arriving as a constraint violation.
    const age = Number(form.ageValue);
    if (!form.ageValue || !Number.isFinite(age) || age <= 0) {
      next.ageValue = "An age has to be more than zero.";
    } else if (age > 900) {
      next.ageValue = "That age looks like a typo.";
    }
    // ck_pets_weight: NULL is fine, zero and negative are not.
    if (form.weightKg !== "" && !(Number(form.weightKg) > 0)) {
      next.weightKg = "A weight has to be more than zero.";
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  async function submit(e) {
    e.preventDefault();
    setSubmitError("");
    if (!validate()) return;

    const body = {
      name: form.name.trim(),
      species: form.species,
      breed: form.breed.trim(),
      ageValue: Number(form.ageValue),
      ageUnit: form.ageUnit,
      sex: form.sex,
      size: form.size,
      // Empty means "not recorded", which the column allows. Sending 0 would
      // be a weight, and ck_pets_weight refuses that one.
      weightKg: form.weightKg === "" ? null : Number(form.weightKg),
      description: form.description.trim() || null,
      neutered: form.neutered,
      microchipped: form.microchipped,
    };

    setBusy(true);
    try {
      const { data } = editing
        ? await api.put(`/my-pets/${pet.id}`, body)
        : await api.post("/my-pets", body);
      onSaved?.(data, editing);
    } catch (err) {
      setSubmitError(apiError(err, "Could not save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={editing ? `Edit ${pet.name}` : "Add a pet from home"}
      subtitle={editing
        ? undefined
        : "For an animal you already own. Shelters never see it until you book a stay."}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button size="sm" type="submit" form="my-pet" disabled={busy}>
            {busy ? "Saving…" : editing ? "Save changes" : "Add pet"}
          </Button>
        </>
      }
    >
      <form id="my-pet" onSubmit={submit} className="space-y-5" noValidate>
        {submitError && (
          <p role="alert" className="rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {submitError}
          </p>
        )}

        <Input label="Name" required value={form.name} onChange={set("name")}
          error={errors.name} placeholder="Rocky" />

        <div className="grid gap-4 sm:grid-cols-2">
          <Select label="Species" required value={form.species} onChange={set("species")}>
            <option value="DOG">Dog</option>
            <option value="CAT">Cat</option>
            <option value="OTHER">Other</option>
          </Select>
          <Input label="Breed" required value={form.breed} onChange={set("breed")}
            error={errors.breed} placeholder="Mixed" />
        </div>

        <div className="grid gap-4 sm:grid-cols-3">
          <Input label="Age" required type="number" min="1" value={form.ageValue}
            onChange={set("ageValue")} error={errors.ageValue} />
          <Select label="Unit" required value={form.ageUnit} onChange={set("ageUnit")}>
            <option value="WEEKS">Weeks</option>
            <option value="MONTHS">Months</option>
            <option value="YEARS">Years</option>
          </Select>
          <Input label="Weight (kg)" type="number" step="0.01" min="0.01"
            value={form.weightKg} onChange={set("weightKg")} error={errors.weightKg}
            hint="Optional" />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Select label="Sex" required value={form.sex} onChange={set("sex")}>
            <option value="MALE">Male</option>
            <option value="FEMALE">Female</option>
          </Select>
          <Select label="Size" required value={form.size} onChange={set("size")}>
            <option value="SMALL">Small</option>
            <option value="MEDIUM">Medium</option>
            <option value="LARGE">Large</option>
            <option value="GIANT">Giant</option>
          </Select>
        </div>

        <Textarea label="Anything a carer should know" rows={3}
          placeholder="Temperament, habits, what they are like with strangers…"
          value={form.description} onChange={set("description")} />

        <div className="flex flex-wrap gap-6 rounded-card bg-pw-input px-4 py-3.5">
          <Checkbox label="Neutered" checked={form.neutered} onChange={set("neutered")} />
          <Checkbox label="Microchipped" checked={form.microchipped} onChange={set("microchipped")} />
        </div>
      </form>
    </Modal>
  );
}
