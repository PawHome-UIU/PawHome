import { FiAlertTriangle, FiEyeOff } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";

/**
 * Confirm removing a pet.
 *
 * TWO DIFFERENT DIALOGS IN ONE, because "you cannot delete this" is an answer
 * the person came here for and not an error to bounce them with. A pet with an
 * application, an adoption or a boarding request against it cannot go: all
 * three reference it ON DELETE RESTRICT and the record has to keep naming the
 * animal it was about. Rather than hiding the menu item and leaving somebody
 * hunting for it, the item is always there and this explains -- then offers
 * the thing they actually wanted, which is the pet off the catalogue.
 */
export default function DeletePetModal({ pet, onClose, onDelete, onUnavailable, busy = false, error = "" }) {
  const blocked = pet.blockingCount > 0;

  return (
    <Modal
      open
      onClose={busy ? () => {} : onClose}
      title={blocked ? `${pet.name} cannot be deleted` : `Delete ${pet.name}?`}
      subtitle={`${pet.breed} · ${pet.shelterName}`}
      size="md"
    >
      {error && (
        <p role="alert" className="mb-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
          {error}
        </p>
      )}

      {blocked ? (
        <>
          <div className="flex gap-3 rounded-card bg-amber-50 p-4">
            <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-700" />
            <p className="text-sm leading-relaxed text-amber-900">
              {pet.blockingCount === 1
                ? "One application, adoption or boarding record"
                : `${pet.blockingCount} applications, adoptions or boarding records`}
              {" "}reference {pet.name}. Those have to keep naming the pet they
              were about, so the record stays.
            </p>
          </div>

          <p className="mt-5 text-[15px] leading-relaxed text-pw-body">
            Taking {pet.name} off the catalogue does what you probably want:
            no new applications, gone from browsing, and every past record intact.
          </p>

          <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
            <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
            <Button onClick={onUnavailable} disabled={busy}>
              <FiEyeOff className="h-4 w-4" /> Take off the catalogue
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="text-[15px] leading-relaxed text-pw-body">
            Nothing references {pet.name}, so this removes the record entirely.
            It cannot be undone.
          </p>

          {/* Named, not implied. Everything else pointing at a pet cascades,
              and somebody deleting a duplicate should know what goes with it. */}
          <div className="mt-5 rounded-card bg-pw-input p-4 text-sm text-pw-body">
            <p className="font-medium text-pw-ink">Deleted along with the pet</p>
            <ul className="mt-2 space-y-1">
              <li>
                {pet.photoCount === 0 ? "No photos" :
                  pet.photoCount === 1 ? "1 photo" : `${pet.photoCount} photos`}
              </li>
              <li>
                {pet.traitLabels.length === 0 ? "No trait chips" :
                  `${pet.traitLabels.length} trait ${pet.traitLabels.length === 1 ? "chip" : "chips"}`}
              </li>
              <li>Any favourites, care logs and vaccination records</li>
            </ul>
          </div>

          <div className="mt-7 flex justify-end gap-3 border-t border-pw-stroke/40 pt-5">
            <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
            <Button onClick={onDelete} disabled={busy}
              className="!bg-pw-danger hover:!bg-pw-danger/90">
              {busy ? "Deleting…" : `Delete ${pet.name}`}
            </Button>
          </div>
        </>
      )}
    </Modal>
  );
}
