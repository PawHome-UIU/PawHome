import { useEffect, useState } from "react";
import { FiImage, FiX } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import Input from "../ui/Input";
import { Select, Textarea, Segmented, ChipGroup, FileDrop } from "../ui/Field";
import api from "../../api/client";
import { API_BASE } from "../../lib/config";

/**
 * Add Pet.
 *
 * THE TRAITS ARE FETCHED, not listed here. They used to be a hard-coded array
 * of the seven V2 seeds, which meant an admin adding an eighth chip had to wait
 * for a frontend deploy to see it -- and, worse, the form sent LABELS while
 * pet_traits stores trait ids. Selecting by label detaches every mapping the
 * first time somebody renames "Friendly" to "Sociable".
 *
 * PHOTOS ARE NOT PART OF THE PET REQUEST. A pet is JSON; each image is its own
 * multipart upload, sent by the page after the pet exists -- a pet has no id to
 * attach a photo to until it has been created, and base64 in the body would
 * inflate every image by a third at both ends.
 *
 * THE COVER INDEX RIDES ALONG because the schema allows at most one cover per
 * pet (uq_pet_cover). "The first one" is not the same thing as "the chosen
 * one", so the server has to be told which.
 */
const EMPTY = {
  name: "", species: "DOG", breed: "", age: "", ageUnit: "Years",
  size: "MEDIUM", sex: "MALE", traits: [], status: "AVAILABLE", description: "",
  weightKg: "", adoptionFee: "", neutered: false, microchipped: false,
};

export default function AddPetModal({
  onSetCover, onDeletePhoto, photoBusy = false, open, onClose, onSave, pet = null, busy = false, error = "" }) {
  const editing = pet != null;

  // Seeded from `pet` when editing.
  //
  // A useState INITIALISER RUNS ONCE, on mount -- it is not re-run when `pet`
  // changes. This component stays mounted while the modal is closed (Modal
  // returns null from inside), so editing Luna, closing, then editing Max would
  // otherwise show Luna's details under Max's title. The call site passes
  // `key={pet?.id}` to force a remount per pet, which is what makes this
  // initialiser correct; if you drop that key, this goes stale silently.
  const [form, setForm] = useState(() =>
    editing
      ? {
          ...EMPTY,
          name: pet.name ?? "",
          breed: pet.breed ?? "",
          species: pet.species ?? "DOG",
          size: pet.size ?? "MEDIUM",
          sex: pet.sex ?? "MALE",
          // ADOPTED and BOARDING are not offered: both need an owner_id
          // (ck_pets_owner_when_placed) and the server refuses to set either
          // from here. Showing the pet's real status would put an option in the
          // list that cannot be saved.
          status: pet.status === "ADOPTED" || pet.status === "BOARDING"
            ? "AVAILABLE" : (pet.status ?? "AVAILABLE"),
          traits: pet.traitIds ?? [],
          description: pet.description ?? "",
          age: String(pet.ageValue ?? ""),
          ageUnit: pet.ageUnit
            ? pet.ageUnit.charAt(0) + pet.ageUnit.slice(1).toLowerCase() : "Years",
          weightKg: pet.weightKg == null ? "" : String(pet.weightKg),
          adoptionFee: pet.adoptionFee == null ? "" : String(pet.adoptionFee),
          neutered: pet.neutered ?? false,
          microchipped: pet.microchipped ?? false,
        }
      : EMPTY
  );
  const [photos, setPhotos] = useState([]);
  const [cover, setCover] = useState(0);
  const [errors, setErrors] = useState({});

  // The real chips, by id. See the note above the component.
  const [traits, setTraits] = useState([]);
  useEffect(() => {
    let cancelled = false;
    api.get("/traits")
      .then(({ data }) => { if (!cancelled) setTraits(data); })
      .catch(() => { if (!cancelled) setTraits([]); });
    return () => { cancelled = true; };
  }, []);

  /** Photos already on the server, when editing. */
  const existing = pet?.photos ?? [];

  const set = (k) => (e) =>
    setForm((f) => ({ ...f, [k]: e?.target ? e.target.value : e }));

  function validate() {
    const next = {};
    if (!form.name.trim()) next.name = "A pet needs a name to be listed.";
    if (!form.breed.trim()) next.breed = "Breed is required.";
    // ck_pets_age is "> 0", not ">= 0": an age of zero is not a young animal,
    // it is a missing answer, and the database says so.
    if (form.age === "" || Number(form.age) <= 0) next.age = "An age has to be more than zero.";
    if (form.weightKg !== "" && Number(form.weightKg) <= 0) next.weightKg = "A weight has to be more than zero.";
    if (form.adoptionFee !== "" && Number(form.adoptionFee) < 0) next.adoptionFee = "A fee cannot be negative.";
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function submit(e) {
    e.preventDefault();
    if (busy || !validate()) return;
    // Nothing is cleared here. The parent owns the request and this form has to
    // still be holding what was typed if the server refuses it.
    onSave?.({
      // Field names match PetRequest exactly; anything else arrives null and
      // trips @NotNull with a message about a field nobody saw.
      pet: {
        name: form.name.trim(),
        species: form.species,
        breed: form.breed.trim(),
        ageValue: Number(form.age),
        ageUnit: form.ageUnit.toUpperCase(),
        sex: form.sex,
        size: form.size,
        weightKg: form.weightKg === "" ? null : Number(form.weightKg),
        description: form.description.trim() || null,
        neutered: form.neutered,
        microchipped: form.microchipped,
        adoptionFee: form.adoptionFee === "" ? 0 : Number(form.adoptionFee),
        traitIds: form.traits,
      },
      status: form.status,
      photos,
      coverIndex: photos.length ? cover : null,
    });
  }

  function close() {
    if (busy) return;
    onClose();
  }

  return (
    <Modal open={open} onClose={close} title={editing ? `Edit ${pet.name}` : "Add Pet"} size="xl">
      <form onSubmit={submit} noValidate>
        {error && (
          <p role="alert" className="mb-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
            {error}
          </p>
        )}

        <p className="mb-1.5 text-sm font-medium text-pw-ink">Pet Gallery</p>
        <div className="flex flex-wrap gap-3">
          <FileDrop
            accept="image/*"
            multiple
            hint="PNG or JPG"
            onChange={(files) => setPhotos((p) => [...p, ...Array.from(files)])}
            className="w-[130px] [&_label]:h-[130px] [&_label]:justify-center [&_label]:px-2 [&_label>span]:text-xs"
          />

          {/* Four slots, as in the design: the filled ones first, then empty
              placeholders so the row keeps its shape while a pet has one photo. */}
          {photos.map((f, i) => (
            <div
              key={i}
              className="relative h-[130px] w-[130px] overflow-hidden rounded-card border border-pw-stroke/60 bg-pw-input"
            >
              <img
                src={URL.createObjectURL(f)}
                alt={`Photo ${i + 1} of ${form.name || "the pet"}`}
                className="h-full w-full object-cover"
              />
              <button
                type="button"
                onClick={() => setCover(i)}
                aria-pressed={i === cover}
                className={[
                  "absolute left-2 top-2 rounded-pill px-2 py-0.5 text-[10px] font-semibold",
                  i === cover ? "bg-pw-button text-white" : "bg-white/90 text-pw-body hover:bg-white",
                ].join(" ")}
              >
                {i === cover ? "Cover" : "Set cover"}
              </button>
              <button
                type="button"
                onClick={() => {
                  setPhotos((p) => p.filter((_, x) => x !== i));
                  setCover((c) => (c >= i && c > 0 ? c - 1 : c));
                }}
                aria-label={`Remove photo ${i + 1}`}
                className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-full bg-white/90 text-pw-body hover:text-pw-danger"
              >
                <FiX className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}

          {/* ALREADY ON THE SERVER, AND NOW ACTIONABLE. These used to be
              read-only under a comment claiming "the page offers both from the
              card" -- it never did, so a shelter was stuck with whichever
              photo landed first as the cover and could not remove one at all.
              An adopter could already do both for their own pet.

              Each button is its own request against a pet that exists, which
              is why they act immediately rather than waiting for Save: there
              is nothing to save them alongside. uq_pet_cover allows one cover
              per pet, so setting a new one clears the old in the same
              transaction -- the server does that, not this. */}
          {existing.map((ph) => (
            <div key={ph.id}
              className="relative h-[130px] w-[130px] overflow-hidden rounded-card border border-pw-stroke/60 bg-pw-input">
              <img src={`${API_BASE}/files/${ph.filePath}`} alt=""
                className="h-full w-full object-cover" />
              {ph.cover ? (
                <span className="absolute left-2 top-2 rounded-pill bg-pw-button px-2 py-0.5 text-[10px] font-semibold text-white">
                  Cover
                </span>
              ) : (
                <button type="button" disabled={photoBusy}
                  onClick={() => onSetCover?.(ph.id)}
                  className="absolute left-2 top-2 rounded-pill bg-white/90 px-2 py-0.5 text-[10px] font-semibold text-pw-body hover:bg-white disabled:opacity-50">
                  Set cover
                </button>
              )}
              <button type="button" disabled={photoBusy}
                aria-label="Delete this photo"
                onClick={() => onDeletePhoto?.(ph.id)}
                className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-full bg-white/90 text-pw-danger hover:bg-white disabled:opacity-50">
                <FiX className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}

          {Array.from({ length: Math.max(0, 3 - photos.length - existing.length) }).map((_, i) => (
            <div
              key={`empty-${i}`}
              aria-hidden="true"
              className="flex h-[130px] w-[130px] items-center justify-center rounded-card bg-pw-input text-pw-stroke"
            >
              <FiImage className="h-6 w-6" />
            </div>
          ))}
        </div>

        <div className="mt-7 grid gap-x-8 gap-y-5 lg:grid-cols-2">
          <div className="space-y-5">
            <Input
              label="Pet Name"
              placeholder="e.g. Buddy"
              value={form.name}
              onChange={set("name")}
              error={errors.name}
            />

            <div className="grid gap-4 sm:grid-cols-2">
              <Select label="Species" value={form.species} onChange={set("species")}>
                <option value="DOG">Dog</option>
                <option value="CAT">Cat</option>
                <option value="OTHER">Other</option>
              </Select>
              <Input
                label="Breed"
                placeholder="e.g. Golden Retriever"
                value={form.breed}
                onChange={set("breed")}
                error={errors.breed}
              />
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <p className="mb-1.5 text-sm font-medium text-pw-ink">Age</p>
                <div className="flex gap-2">
                  <Input
                    type="number"
                    min="0"
                    placeholder="2"
                    value={form.age}
                    onChange={set("age")}
                    error={errors.age}
                    className="flex-1"
                    aria-label="Age"
                  />
                  <Select
                    value={form.ageUnit}
                    onChange={set("ageUnit")}
                    aria-label="Age unit"
                    className="w-[110px]"
                  >
                    <option>Years</option>
                    <option>Months</option>
                    <option>Weeks</option>
                  </Select>
                </div>
              </div>

              <Select label="Size" value={form.size} onChange={set("size")}>
                <option value="SMALL">Small</option>
                <option value="MEDIUM">Medium</option>
                <option value="LARGE">Large</option>
                <option value="GIANT">Giant</option>
              </Select>
            </div>

            <Segmented
              label="Gender"
              name="pet-sex"
              value={form.sex}
              onChange={(v) => setForm((f) => ({ ...f, sex: v }))}
              options={[{ value: "MALE", label: "Male" }, { value: "FEMALE", label: "Female" }]}
            />
          </div>

          <div className="space-y-5">
            <ChipGroup
              label="Personality Traits"
              options={traits.map((t) => ({ value: t.id, label: t.label }))}
              value={form.traits}
              onChange={(v) => setForm((f) => ({ ...f, traits: v }))}
            />

            <Select label="Adoption Status" value={form.status} onChange={set("status")}>
              <option value="AVAILABLE">Available</option>
              <option value="PENDING">Pending</option>
              <option value="UNAVAILABLE">Unavailable</option>
            </Select>
            {/* ADOPTED AND BOARDING ARE BOTH ABSENT, for the same reason:
                ck_pets_owner_when_placed requires an owner_id for either, and
                the owner is decided by an approved adoption or an accepted
                boarding request. Offering them here would mean asking staff to
                name the owner in a dropdown -- so the server refuses both, and
                a control that cannot be saved does not belong on the form. */}

            <div className="grid gap-4 sm:grid-cols-2">
              <Input label="Weight (kg)" type="number" step="0.01" min="0"
                placeholder="Optional" value={form.weightKg}
                onChange={set("weightKg")} error={errors.weightKg} />
              <Input label="Adoption fee (৳)" type="number" step="0.01" min="0"
                placeholder="0.00" value={form.adoptionFee}
                onChange={set("adoptionFee")} error={errors.adoptionFee} />
            </div>

            <div className="flex flex-wrap gap-2">
              {[["neutered", "Neutered"], ["microchipped", "Microchipped"]].map(([k, label]) => (
                <button key={k} type="button" role="checkbox" aria-checked={form[k]}
                  onClick={() => setForm((f) => ({ ...f, [k]: !f[k] }))}
                  className={[
                    "rounded-pill border px-4 py-2 text-sm transition-colors",
                    form[k]
                      ? "border-pw-button bg-pw-sidebar/35 font-medium text-pw-ink"
                      : "border-pw-stroke/60 bg-white text-pw-body hover:border-pw-stroke",
                  ].join(" ")}>
                  {label}
                </button>
              ))}
            </div>

            <Textarea
              label="Description"
              rows={6}
              placeholder="Describe the pet's history, health, and ideal home…"
              value={form.description}
              onChange={set("description")}
            />
          </div>
        </div>

        <div className="mt-8 flex flex-wrap justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="submit" disabled={busy}>
            {busy ? "Saving…" : editing ? "Save changes" : "Add Pet"}
          </Button>
          <Button type="button" variant="outline" onClick={close} disabled={busy}
            className="!border-pw-danger !text-pw-danger">
            Cancel
          </Button>
        </div>
      </form>
    </Modal>
  );
}
