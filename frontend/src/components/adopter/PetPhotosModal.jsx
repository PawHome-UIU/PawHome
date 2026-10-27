import { useEffect, useState } from "react";
import { FiStar, FiTrash2, FiUploadCloud, FiImage, FiLock } from "react-icons/fi";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * Pictures of a pet you own.
 *
 * SEPARATE FROM THE DETAILS FORM, because a photo needs a pet id to belong to
 * and a new pet does not have one until it is saved. Folding uploads into the
 * add form would mean holding files in the browser through a create, then
 * firing a second request that can fail on its own -- and a half-added pet
 * with no picture and an error is worse than two clear steps.
 *
 * THE COVER IS A DATABASE RULE, NOT A UI ONE. uq_pet_cover -- a generated
 * column that is 1 for a cover row and NULL otherwise -- allows exactly one
 * cover per pet, so "make this the cover" is a server operation that clears
 * the old one in the same transaction. This dialog just asks.
 */
export default function PetPhotosModal({ open, onClose, pet, onChanged }) {
  const [photos, setPhotos] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open || !pet) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/my-pets/${pet.id}`);
        if (!cancelled) { setPhotos(data.photos); setError(""); }
      } catch (err) {
        if (!cancelled) setError(apiError(err, "Could not load the photos."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [open, pet]);

  /** Every mutation returns the whole detail, so the list is never guessed. */
  async function run(fn, failure) {
    setBusy(true);
    setError("");
    try {
      const { data } = await fn();
      setPhotos(data.photos);
      onChanged?.();
    } catch (err) {
      setError(apiError(err, failure));
    } finally {
      setBusy(false);
    }
  }

  function upload(e) {
    const file = e.target.files?.[0];
    // Cleared straight away so choosing the same file twice still fires a
    // change event -- otherwise a failed upload cannot be retried.
    e.target.value = "";
    if (!file) return;
    const body = new FormData();
    body.append("file", file);
    run(() => api.post(`/my-pets/${pet.id}/photos`, body), "Could not upload that.");
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={pet ? `Photos of ${pet.name}` : "Photos"}
      subtitle="The starred one is what everybody sees first."
      footer={<Button size="sm" onClick={onClose}>Done</Button>}
    >
      {error && (
        <p role="alert" className="mb-4 rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
          {error}
        </p>
      )}

      <label className={`flex cursor-pointer flex-col items-center gap-2 rounded-card border border-dashed
        border-pw-stroke bg-pw-input/50 px-6 py-7 text-center transition-colors hover:border-pw-button
        ${busy ? "pointer-events-none opacity-60" : ""}`}>
        <FiUploadCloud className="h-6 w-6 text-pw-button" />
        <span className="text-[15px] font-medium text-pw-ink">
          {busy ? "Working…" : "Choose a picture"}
        </span>
        <span className="text-sm text-pw-muted">JPG, PNG or WebP.</span>
        <input type="file" accept="image/*" className="sr-only"
          onChange={upload} disabled={busy} />
      </label>

      {loading ? (
        <p className="mt-5 text-center text-sm text-pw-muted">Loading…</p>
      ) : photos.length === 0 ? (
        <p className="mt-5 flex items-center justify-center gap-2 text-sm text-pw-muted">
          <FiImage className="h-4 w-4" /> No pictures yet.
        </p>
      ) : (
        <ul className="mt-5 grid gap-4 sm:grid-cols-3">
          {photos.map((ph) => (
            <li key={ph.id} className="overflow-hidden rounded-card border border-pw-stroke/50">
              <div className="relative aspect-square bg-pw-sidebar/25 overflow-hidden shrink-0">
                <img src={`${API_BASE}/files/${ph.filePath}`} alt=""
                  className="absolute inset-0 h-full w-full object-cover" />
                {ph.cover && (
                  <span className="absolute left-2 top-2 flex items-center gap-1 rounded-pill bg-white/95 px-2 py-0.5 text-[10px] font-semibold text-pw-button">
                    <FiStar className="h-3 w-3" /> Cover
                  </span>
                )}
                {/* THE SHELTER'S OWN PICTURE, marked so the missing delete
                    button below reads as a rule rather than a bug. */}
                {!ph.deletable && (
                  <span className="absolute right-2 top-2 flex items-center gap-1 rounded-pill bg-white/95 px-2 py-0.5 text-[10px] font-semibold text-pw-muted">
                    <FiLock className="h-3 w-3" /> Shelter
                  </span>
                )}
              </div>
              <div className="flex gap-1.5 p-2">
                <Button variant="outline" size="sm" className="!px-2" disabled={busy || ph.cover}
                  aria-label="Make this the cover"
                  onClick={() => run(
                    () => api.post(`/my-pets/${pet.id}/photos/${ph.id}/cover`),
                    "Could not set the cover.")}>
                  <FiStar className="h-3.5 w-3.5" />
                </Button>
                {/* `deletable` comes from the server, which compares
                    pet_photos.uploaded_by against the signed-in user -- the
                    page does not work it out from the shelter name. The server
                    checks again on the request: a button that is not drawn is
                    not a rule. */}
                {ph.deletable && (
                  <Button variant="outline" size="sm" className="!px-2 !border-pw-danger !text-pw-danger"
                    disabled={busy} aria-label="Delete this photo"
                    onClick={() => run(
                      () => api.delete(`/my-pets/${pet.id}/photos/${ph.id}`),
                      "Could not delete that.")}>
                    <FiTrash2 className="h-3.5 w-3.5" />
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {/* Deleting the cover promotes the next photo server-side, so a pet with
          pictures is never left showing the empty placeholder. */}
      <p className="mt-5 text-xs text-pw-muted">
        Removing the cover picture promotes the next one automatically.
        {photos.some((ph) => !ph.deletable) && (
          <> Pictures marked <span className="font-medium">Shelter</span> were
          uploaded by the shelter this pet was adopted from — they are part of
          that record, so they cannot be removed here. You can still make one
          the cover.</>
        )}
      </p>
    </Modal>
  );
}
