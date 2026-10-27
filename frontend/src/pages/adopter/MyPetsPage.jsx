import { useCallback, useEffect, useState } from "react";
import {
  FiPlus, FiImage, FiEdit2, FiTrash2, FiHome, FiRefreshCw, FiCalendar, FiCamera,
  FiActivity,
} from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import MyPetModal from "../../components/adopter/MyPetModal";
import PetPhotosModal from "../../components/adopter/PetPhotosModal";
import PetHealthModal from "../../components/adopter/PetHealthModal";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Modal from "../../components/ui/Modal";
import SuccessModal from "../../components/ui/SuccessModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * Every animal this person owns -- however it came to them.
 *
 * TWO KINDS IN ONE LIST. A pet adopted through PawHome carries a shelter of
 * record; a pet registered from home has shelter_id NULL. Both are the
 * owner's to EDIT -- the animal's weight and its photographs are facts about
 * the pet now, and the shelter stopped being the one who knows them the day it
 * was rehomed. What the shelter WROTE is protected a level down instead: an
 * owner cannot delete a photo the shelter uploaded, or a vaccination it
 * recorded. Only the household pet can be deleted outright, because an
 * adoption names the adopted row.
 *
 * The server decides both and says so in `editable` and `removable`, rather
 * than the page inferring them from the shelter name and drifting apart from
 * the rules the service actually enforces.
 *
 * WHY THIS PAGE EXISTS AT ALL: boarding is for an animal somebody owns, and
 * before V9 the only way a pets row could gain an owner was an approved
 * adoption. Somebody who has had a cat for nine years could not book a single
 * night. This is where they say the cat exists.
 */
const STATUS_LABEL = {
  OWNED: "Yours",
  ADOPTED: "Adopted",
  BOARDING: "Boarding now",
};

const UNIT = { WEEKS: "week", MONTHS: "month", YEARS: "year" };

const age = (value, unit) =>
  `${value} ${UNIT[unit] ?? "year"}${value === 1 ? "" : "s"} old`;

export default function MyPetsPage() {
  const [pets, setPets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [editing, setEditing] = useState(null);   // { pet } or { pet: null }
  const [removing, setRemoving] = useState(null);
  const [photosFor, setPhotosFor] = useState(null);
  const [healthFor, setHealthFor] = useState(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/my-pets");
      setPets(data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your pets."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/my-pets");
        if (!cancelled) setPets(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your pets."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  async function remove() {
    setBusy(true);
    try {
      await api.delete(`/my-pets/${removing.id}`);
      setRemoving(null);
      await load();
      setNotice({
        title: "Pet removed",
        message: `${removing.name} is no longer on your list.`,
      });
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
      setRemoving(null);
    } finally {
      setBusy(false);
    }
  }

  const household = pets.filter((p) => p.shelterId == null);

  return (
    <AdopterLayout
      title="My Pets"
      subtitle="Animals you have adopted, and animals you already had."
      actions={
        <div className="flex items-center gap-3">
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          <Button size="sm" onClick={() => setEditing({ pet: null })}>
            <FiPlus className="h-4 w-4" /> Add a pet from home
          </Button>
        </div>
      }
    >
      {loadError ? (
        <Card className="px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      ) : loading ? (
        <Card className="px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </Card>
      ) : pets.length === 0 ? (
        <Card className="px-6 py-16 text-center">
          <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-pw-input text-pw-button">
            <FiHome className="h-6 w-6" />
          </span>
          <p className="mt-4 text-[15px] font-medium text-pw-ink">No pets yet.</p>
          <p className="mt-1.5 text-sm text-pw-muted">
            Add one you already own, or adopt one through PawHome — either way it
            shows up here and can be booked in for boarding.
          </p>
          <div className="mt-6 flex justify-center gap-3">
            <Button size="sm" onClick={() => setEditing({ pet: null })}>Add a pet from home</Button>
            <Button size="sm" variant="outline" to="/app/pets">Browse pets</Button>
          </div>
        </Card>
      ) : (
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {pets.map((p) => (
            <Card key={p.id} className="flex h-full flex-col overflow-hidden">
              <div className="relative aspect-[16/10] bg-pw-sidebar/25 overflow-hidden shrink-0">
                {p.coverPhotoPath ? (
                  <img src={`${API_BASE}/files/${p.coverPhotoPath}`} alt=""
                    className="absolute inset-0 h-full w-full object-cover" />
                ) : (
                  <span className="flex h-full w-full items-center justify-center">
                    <FiImage className="h-6 w-6 text-pw-stroke" />
                  </span>
                )}
                <span className="absolute right-3 top-3 rounded-pill bg-white/95 px-2.5 py-1 text-[11px] font-semibold text-pw-button">
                  {STATUS_LABEL[p.status] ?? p.status}
                </span>
              </div>

              <div className="flex flex-1 flex-col p-5">
                <div className="flex items-start justify-between gap-3">
                  <h2 className="text-lg font-semibold leading-tight">{p.name}</h2>
                  <span className="rounded-pill bg-pw-input px-2.5 py-1 text-xs text-pw-button">
                    {p.breed}
                  </span>
                </div>
                <p className="mt-1 text-sm text-pw-muted">
                  {age(p.ageValue, p.ageUnit)} · {p.sex === "MALE" ? "Male" : "Female"}
                  {p.weightKg != null && ` · ${Number(p.weightKg)} kg`}
                </p>

                {/* The shelter of record, or the absence of one. This line is
                    the whole difference between the two kinds of row. */}
                <p className="mt-2 text-sm text-pw-body">
                  {p.shelterName
                    ? <>Adopted from <span className="font-medium">{p.shelterName}</span></>
                    : "Registered by you"}
                </p>

                {p.description && (
                  <p className="mt-3 line-clamp-3 text-sm leading-relaxed text-pw-muted">
                    {p.description}
                  </p>
                )}

                <div className="mt-auto flex flex-wrap gap-2 pt-5">
                  <Button variant="outline" size="sm" className="flex-1" to="/app/boarding">
                    <FiCalendar className="h-4 w-4" /> Boarding
                  </Button>
                  {/* Offered for BOTH kinds. An adopted pet's record is the
                      shelter's to write and the owner's to read, so the button
                      is here either way -- the modal decides what to draw. */}
                  <Button variant="outline" size="sm" className="flex-1"
                    onClick={() => setHealthFor(p)}>
                    <FiActivity className="h-4 w-4" /> Health
                  </Button>
                  {/* TWO FLAGS, NOT ONE, and both come from the server rather
                      than being worked out here from the shelter name.

                      editable  -- the pet is yours and is not away on a
                      boarding stay. TRUE FOR AN ADOPTED PET: the animal's
                      weight, description and photographs are facts about the
                      pet now, and the shelter stopped being the one who knows
                      them the day it was rehomed.

                      removable -- household pets only. Deleting removes the
                      row an adoption NAMES, which fk_adoptions_pet refuses
                      anyway; this is that refusal arriving as an absent
                      button instead of an error. */}
                  {p.editable && (
                    <>
                      <Button variant="outline" size="sm" aria-label={`Photos of ${p.name}`}
                        onClick={() => setPhotosFor(p)}>
                        <FiCamera className="h-4 w-4" />
                      </Button>
                      <Button variant="outline" size="sm" aria-label={`Edit ${p.name}`}
                        onClick={() => setEditing({ pet: p })}>
                        <FiEdit2 className="h-4 w-4" />
                      </Button>
                    </>
                  )}
                  {p.removable && (
                    <Button variant="outline" size="sm" aria-label={`Remove ${p.name}`}
                      className="!border-pw-danger !text-pw-danger"
                      onClick={() => setRemoving(p)}>
                      <FiTrash2 className="h-4 w-4" />
                    </Button>
                  )}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {!loading && !loadError && pets.length > 0 && household.length === 0 && (
        <Card className="mt-6 px-6 py-8 text-center" tinted>
          <p className="text-[15px] font-medium text-pw-ink">
            Have a pet that did not come from a shelter?
          </p>
          <p className="mt-1.5 text-sm text-pw-muted">
            Add it here and you can book boarding for it like any other.
          </p>
          <Button size="sm" className="mt-5" onClick={() => setEditing({ pet: null })}>
            Add a pet from home
          </Button>
        </Card>
      )}

      {/* Mounted only while open, and keyed, so each opening starts from a
          fresh initialiser rather than an effect refilling the fields. */}
      {editing && <MyPetModal
        key={editing.pet?.id ?? "new"}
        open
        pet={editing.pet}
        onClose={() => setEditing(null)}
        onSaved={(saved, wasEditing) => {
          setEditing(null);
          load();
          setNotice(wasEditing
            ? { title: "Saved", message: `${saved.name}'s details are up to date.` }
            : { title: `${saved.name} added`,
                message: "You can book boarding for them from the Boarding page." });
        }}
      />}

      <PetHealthModal
        open={healthFor != null}
        pet={healthFor}
        onClose={() => setHealthFor(null)}
        onChanged={load}
      />

      <PetPhotosModal
        open={photosFor != null}
        pet={photosFor}
        onClose={() => setPhotosFor(null)}
        // The card shows the cover, so the list has to move with it.
        onChanged={load}
      />

      <Modal
        open={removing != null}
        onClose={() => setRemoving(null)}
        title={removing ? `Remove ${removing.name}?` : ""}
        subtitle="This deletes the record. It cannot be undone."
        size="sm"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setRemoving(null)} disabled={busy}>
              Cancel
            </Button>
            <Button size="sm" className="!bg-pw-danger hover:!bg-rose-700"
              disabled={busy} onClick={remove}>
              {busy ? "Removing…" : "Remove"}
            </Button>
          </>
        }
      >
        <p className="text-[15px] leading-relaxed text-pw-body">
          Only a pet with no boarding history can be removed — a stay from last
          winter still has to name the animal it was about, so the server
          refuses if there is one.
        </p>
      </Modal>

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? ""}
      />
    </AdopterLayout>
  );
}
