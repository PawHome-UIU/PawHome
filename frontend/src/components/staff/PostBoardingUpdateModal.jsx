import { useState } from "react";
import { FiCoffee, FiSun, FiPlusSquare, FiActivity } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { Textarea, FileDrop } from "../ui/Field";

/**
 * Post a boarding update.
 *
 * This is the missing producer for a screen that already exists: the adopter's
 * Boarding Details page renders a live timeline of boarding_updates -- "Morning
 * Playtime", "Feeding", "Arrival Check-in" -- and until now nothing in the
 * staff portal could write one. The timeline was a display with no source.
 *
 * The types are the boarding_updates.update_type enum, MINUS TWO. The enum
 * has six; ARRIVAL_CHECK_IN and DEPARTURE are not offered here because they
 * are written by checking the pet in and out. They are the record OF those
 * events, so posting one by hand would put an arrival note on a stay that has
 * not started -- and the server refuses them on this route for that reason.
 *
 * The rest are a fixed list rather than free text because the adopter's
 * timeline renders a different icon per type, and a seventh type invented here
 * would arrive there with no icon at all.
 */
const TYPES = [
  { value: "FEEDING", label: "Feeding", icon: FiCoffee,
    hint: "A meal, and how much of it they ate." },
  { value: "PLAYTIME", label: "Playtime", icon: FiSun,
    hint: "Exercise, yard time, how they were with other animals." },
  { value: "MEDICATION", label: "Medication", icon: FiPlusSquare,
    hint: "A dose given, with the time." },
  { value: "HEALTH", label: "Health", icon: FiActivity,
    hint: "Anything the owner would want to know about right away." },
];

export default function PostBoardingUpdateModal({ open, onClose, boarding, onPost, busy = false }) {
  const [type, setType] = useState("");
  const [note, setNote] = useState("");
  const [photo, setPhoto] = useState(null);
  const [touched, setTouched] = useState(false);

  const chosen = TYPES.find((t) => t.value === type);
  const noType = !type;
  // The note is the update. A type on its own tells the owner "something
  // happened, in this category" -- which is worse than no notification.
  const tooShort = note.trim().length < 10;
  const blocked = noType || tooShort;

  function submit(e) {
    e.preventDefault();
    setTouched(true);
    if (blocked) return;
    // The parent posts and closes -- see the note in DeclineBoardingModal.
    onPost?.({ boardingId: boarding?.id, type, note: note.trim(), photo });
  }

  function reset() {
    setType("");
    setNote("");
    setPhoto(null);
    setTouched(false);
  }

  function close() {
    reset();
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Post an update"
      subtitle={
        boarding
          ? `${boarding.petName} · ${boarding.ownerName ?? "the owner"} will see this straight away`
          : "The owner will see this straight away"
      }
      size="md"
    >
      <form onSubmit={submit} noValidate>
        <fieldset>
          <legend className="mb-2 text-sm font-medium text-pw-ink">
            What happened?
          </legend>
          <div className="grid gap-2.5 sm:grid-cols-2">
            {TYPES.map(({ value, label, icon: Icon }) => {
              const on = type === value;
              return (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={on}
                  onClick={() => setType(value)}
                  className={[
                    "flex items-center gap-2.5 rounded-pill border px-4 py-3 text-left text-sm transition-colors",
                    on
                      ? "border-pw-button bg-pw-input font-medium text-pw-button"
                      : "border-pw-stroke/60 bg-white text-pw-body hover:border-pw-stroke",
                  ].join(" ")}
                >
                  <Icon className={`h-[18px] w-[18px] shrink-0 ${on ? "text-pw-button" : "text-pw-muted"}`} />
                  {label}
                </button>
              );
            })}
          </div>
        </fieldset>
        {touched && noType && (
          <p className="mt-2 text-sm text-pw-danger">Pick what this update is about.</p>
        )}

        <Textarea
          label="Note to the owner"
          className="mt-5"
          required
          rows={4}
          placeholder={chosen?.hint ?? "Tell the owner how their pet is doing…"}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          onBlur={() => setTouched(true)}
          aria-invalid={touched && tooShort}
        />
        {touched && tooShort && (
          <p className="mt-1.5 text-sm text-pw-danger">
            Write at least a sentence — this is the whole update as far as the owner is concerned.
          </p>
        )}

        <FileDrop
          label="Photo (optional)"
          className="mt-5"
          accept="image/*"
          hint="JPG or PNG. Owners open these first."
          value={photo}
          onChange={setPhoto}
        />

        <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
          <Button type="button" variant="outline" onClick={close} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={blocked || busy}>
            {busy ? "Posting…" : "Post update"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
