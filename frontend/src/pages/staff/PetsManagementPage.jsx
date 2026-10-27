import { useCallback, useEffect, useMemo, useState } from "react";
import {
  FiPlus, FiGrid, FiList, FiRefreshCw, FiEdit2, FiEye, FiEyeOff, FiClock, FiImage,
  FiTrash2,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import StatusBadge from "../../components/pets/StatusBadge";
import DataTable from "../../components/admin/DataTable";
import ActionMenu from "../../components/ui/ActionMenu";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Dropdown from "../../components/ui/Dropdown";
import AddPetModal from "../../components/staff/AddPetModal";
import DeletePetModal from "../../components/staff/DeletePetModal";
import SuccessModal from "../../components/ui/SuccessModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * The shelter's animals.
 *
 * READS THE REAL DATABASE. It used to render a hard-coded array from
 * data/staff.js and the Add Pet modal threw its result away, so nothing a staff
 * member typed here ever reached a table.
 *
 * SCOPED SERVER-SIDE, NOT HERE. The list arrives already limited to the
 * caller's own shelter -- there is no "my shelter" filter in this file and
 * there must not be one, because a filter in a React component is a gate anyone
 * can walk around with devtools. /api/v1/staff/pets is what enforces it.
 *
 * SAVING A PET IS UP TO THREE REQUESTS, and they cannot be collapsed into one:
 * the pet is JSON, each photo is its own multipart upload, and a status change
 * has its own endpoint because most of the statuses are not the staff's to set.
 * A pet also has no id to attach a photo to until it has been created.
 */
const PLACEHOLDER = "flex h-full w-full items-center justify-center text-pw-stroke";

/** "2 years", from the two columns the schema actually stores. */
const ageOf = (p) => {
  const unit = p.ageUnit.toLowerCase();
  return `${p.ageValue} ${p.ageValue === 1 ? unit.replace(/s$/, "") : unit}`;
};

const photoUrl = (path) => `${API_BASE}/files/${path}`;

export default function PetsManagementPage() {
  const [view, setView] = useState("grid");
  const [status, setStatus] = useState(null);
  const [species, setSpecies] = useState(null);

  const [pets, setPets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [busyId, setBusyId] = useState(null);
  const [photoBusy, setPhotoBusy] = useState(false);

  const [addOpen, setAddOpen] = useState(false);
  const [editing, setEditing] = useState(null);   // a PetDetail
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [deleting, setDeleting] = useState(null);
  const [deleteError, setDeleteError] = useState("");
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/staff/pets");
      setPets(data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your shelter's pets."));
    } finally {
      setLoading(false);
    }
  }, []);

  // The first load runs inline rather than calling load(), so no state is set
  // synchronously while the effect body is running.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/staff/pets");
        if (!cancelled) setPets(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your shelter's pets."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /**
   * Create or update, then the photos, then the status.
   *
   * THE ORDER IS FORCED. A new pet has no id until the POST returns, so its
   * photos cannot be uploaded before it exists. The status goes last because
   * it is a separate endpoint: PetRequest carries no status at all, since
   * ADOPTED and BOARDING both need an owner_id that only an adoption or a
   * boarding request can supply.
   *
   * A PHOTO THAT FAILS DOES NOT LOSE THE PET. The pet is already saved by
   * then, so the upload is reported on its own and the rest of the work
   * stands -- rolling it back would throw away everything that worked.
   */
  async function save({ pet, status: wanted, photos, coverIndex }) {
    setSaving(true);
    setSaveError("");
    try {
      const { data } = editing
        ? await api.put(`/staff/pets/${editing.pet.id}`, pet)
        : await api.post("/staff/pets", pet);

      const id = data.pet.id;
      let photoTrouble = "";

      for (let i = 0; i < photos.length; i++) {
        const body = new FormData();
        body.append("file", photos[i]);
        try {
          // Let the browser set the multipart boundary; naming the content
          // type by hand omits it and the server cannot parse the parts.
          await api.post(`/staff/pets/${id}/photos?cover=${i === coverIndex}`, body);
        } catch (err) {
          photoTrouble = apiError(err, "One photo could not be uploaded.");
        }
      }

      if (wanted && wanted !== data.pet.status) {
        await api.post(`/staff/pets/${id}/status`, { status: wanted });
      }

      setAddOpen(false);
      setEditing(null);
      await load();
      setNotice({
        title: editing ? `${pet.name} updated` : `${pet.name} added`,
        message: photoTrouble
          ? `Saved, but a photo did not upload: ${photoTrouble}`
          : "The pet is on your shelter's list.",
      });
    } catch (err) {
      setSaveError(apiError(err, "That pet could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  /** Open the editor, fetching the pet's photos first. */
  async function openEditor(row) {
    setSaveError("");
    if (!row) { setEditing(null); setAddOpen(true); return; }
    setBusyId(row.id);
    try {
      const { data } = await api.get(`/staff/pets/${row.id}`);
      setEditing(data);
    } catch (err) {
      setNotice({ title: "Could not open that pet", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  /**
   * Cover and delete act at once, on the pet already open in the editor.
   *
   * Both return the whole PetDetail, so `editing` is replaced with the
   * server's answer rather than patched -- deleting the cover promotes the
   * next photo server-side, and guessing which one that is here would be a
   * second implementation of a rule that already exists.
   */
  async function photoAction(request) {
    setPhotoBusy(true);
    try {
      const { data } = await request();
      setEditing(data);
      // The grid behind the modal shows the cover, so it moves too.
      await load();
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setPhotoBusy(false);
    }
  }

  async function changeStatus(id, next, label) {
    setBusyId(id);
    try {
      await api.post(`/staff/pets/${id}/status`, { status: next });
      await load();
      setNotice({ title: label });
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  /**
   * Remove a pet outright.
   *
   * The modal has already told the person what goes with it. A 409 here means
   * something started referencing the pet between the list loading and this
   * click -- rare, and the modal stays open showing why.
   */
  async function removePet(id) {
    setBusyId(id);
    setDeleteError("");
    try {
      await api.delete(`/staff/pets/${id}`);
      setDeleting(null);
      await load();
      setNotice({ title: "Pet deleted", message: "The record and its photos are gone." });
    } catch (err) {
      setDeleteError(apiError(err, "That pet could not be deleted."));
    } finally {
      setBusyId(null);
    }
  }

  const rows = useMemo(() => pets.filter(
    (p) => (!status || p.status === status.toUpperCase()) &&
           (!species || p.species === species.toUpperCase()),
  ), [pets, status, species]);

  /** The menu for one pet, shared by the card and the table row. */
  const menuFor = (p) => [
    p.status !== "AVAILABLE" && !p.ownerId && {
      label: "Mark available", icon: FiEye,
      onClick: () => changeStatus(p.id, "AVAILABLE", `${p.name} is available`),
    },
    p.status === "AVAILABLE" && {
      label: "Mark pending", icon: FiClock,
      onClick: () => changeStatus(p.id, "PENDING", `${p.name} is pending`),
    },
    p.status !== "UNAVAILABLE" && !p.ownerId && {
      // How a pet with a history leaves the catalogue. Delete below is for the
      // ones that have no history to keep.
      label: "Take off the catalogue", icon: FiEyeOff,
      onClick: () => changeStatus(p.id, "UNAVAILABLE", `${p.name} is off the catalogue`),
    },
    {
      // ALWAYS OFFERED, even on a pet that cannot be deleted. The modal is
      // what explains why and points at the alternative -- hiding the item
      // instead just sends somebody hunting for a menu entry that is not
      // there, which is exactly how this question got asked.
      label: "Delete pet", icon: FiTrash2, tone: "danger",
      onClick: () => { setDeleteError(""); setDeleting(p); },
    },
  ];

  return (
    <StaffLayout
      title="Manage Pets"
      subtitle={loading ? "Loading…" : `${pets.length} in your shelter`}
      actions={
        <>
          <div className="flex gap-1 rounded-pill bg-pw-input p-1">
            {[["grid", FiGrid], ["list", FiList]].map(([k, Icon]) => (
              <button key={k} type="button" onClick={() => setView(k)} aria-pressed={view === k}
                aria-label={`${k} view`}
                className={`flex h-8 items-center gap-1.5 rounded-pill px-3.5 text-sm capitalize ${
                  view === k ? "bg-white font-medium text-pw-button shadow-card" : "text-pw-body"}`}>
                <Icon className="h-4 w-4" /> {k}
              </button>
            ))}
          </div>
          <Dropdown label="Status" value={status} onChange={setStatus} anyLabel="All statuses"
            options={["Available", "Pending", "Adopted", "Boarding", "Unavailable"]} />
          <Dropdown label="Species" value={species} onChange={setSpecies} anyLabel="All species"
            options={["Dog", "Cat", "Other"]} />
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          <Button size="sm" onClick={() => openEditor(null)}>
            <FiPlus className="h-4 w-4" /> Add Pet
          </Button>
        </>
      }
    >
      {loadError ? (
        <Card className="px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      ) : loading ? (
        <Card className="px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading pets…</p>
        </Card>
      ) : rows.length === 0 ? (
        <Card className="px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-ink">
            {pets.length === 0 ? "No pets in your shelter yet." : "No pets match those filters."}
          </p>
          <p className="mt-1.5 text-sm text-pw-muted">
            {pets.length === 0
              ? "Add the first one and it will appear here."
              : "Clear a filter to see the rest of the shelter."}
          </p>
        </Card>
      ) : view === "grid" ? (
        // Three per row, not four. With the 264px sidebar taking its share, a
        // four-column grid gives each card ~250px -- the name, the status badge
        // and the trait chips all end up fighting for that width.
        <div className="grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
          {rows.map((p) => (
            <Card key={p.id} className="flex h-full flex-col overflow-hidden">
              <div className="relative aspect-[4/3] bg-pw-sidebar/25 overflow-hidden shrink-0">
                {p.coverPhotoPath ? (
                  <img src={photoUrl(p.coverPhotoPath)} alt={p.name}
                    className="absolute inset-0 h-full w-full object-cover" />
                ) : (
                  <span className={PLACEHOLDER}><FiImage className="h-8 w-8" /></span>
                )}
                <span className="absolute left-3 top-3"><StatusBadge status={p.status} /></span>
              </div>
              <div className="flex flex-1 flex-col p-5">
                <div className="flex items-start justify-between gap-2">
                  <h3 className="text-lg font-semibold leading-tight">{p.name}</h3>
                  <ActionMenu disabled={busyId === p.id} label={`Actions for ${p.name}`}
                    items={menuFor(p)} />
                </div>
                <p className="mt-1 text-sm text-pw-muted">{p.breed} · {ageOf(p)}</p>
                <div className="mt-3 flex flex-wrap gap-2">
                  {p.traitLabels.map((t) => (
                    <span key={t} className="rounded-pill bg-pw-input px-2.5 py-1 text-[11px] uppercase tracking-wide text-pw-body">
                      {t}
                    </span>
                  ))}
                </div>
                <div className="mt-auto pt-5">
                  <Button variant="outline" size="sm" full disabled={busyId === p.id}
                    onClick={() => openEditor(p)}>
                    <FiEdit2 className="h-3.5 w-3.5" /> Edit Profile
                  </Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <DataTable
          title="Pets in your shelter"
          resetKey={status ?? "all"}
          pageSize={6}
          columns={[
            { key: "name", label: "Pet" },
            { key: "breed", label: "Breed" },
            { key: "age", label: "Age" },
            { key: "traits", label: "Traits" },
            { key: "status", label: "Status" },
            { key: "actions", label: "", align: "right", className: "w-px whitespace-nowrap" },
          ]}
          rows={rows}
          render={(row, key) => {
            if (key === "name") {
              return (
                <div className="flex items-center gap-3">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-card bg-pw-sidebar/25">
                    {row.coverPhotoPath
                      ? <img src={photoUrl(row.coverPhotoPath)} alt="" className="h-full w-full object-cover" />
                      : <FiImage className="h-4 w-4 text-pw-stroke" />}
                  </span>
                  <span className="font-medium">{row.name}</span>
                </div>
              );
            }
            if (key === "age") return ageOf(row);
            if (key === "status") return <StatusBadge status={row.status} />;
            if (key === "traits") {
              return (
                <span className="flex flex-wrap gap-1.5">
                  {row.traitLabels.map((t) => (
                    <span key={t} className="rounded-pill bg-pw-input px-2 py-0.5 text-[11px] text-pw-body">{t}</span>
                  ))}
                </span>
              );
            }
            if (key === "actions") {
              return (
                <span className="flex flex-nowrap items-center justify-end gap-2">
                  <Button variant="outline" size="sm" className="!h-8 !w-[86px] !px-0"
                    disabled={busyId === row.id} onClick={() => openEditor(row)}>
                    <FiEdit2 className="h-3.5 w-3.5" /> Edit
                  </Button>
                  <ActionMenu disabled={busyId === row.id}
                    label={`Actions for ${row.name}`} items={menuFor(row)} />
                </span>
              );
            }
            return row[key];
          }}
        />
      )}

      {/* Edit is the same form as Add -- the fields a pet has do not change
          depending on whether it already exists. Both are mounted only while
          open, which is what makes AddPetModal's useState initialiser correct:
          it runs on mount, so a fresh mount per pet is what stops the second
          pet you edit from opening with the first one's details. */}
      {editing && (
        <AddPetModal
          key={editing.pet.id}
          open
          pet={{ ...editing.pet, photos: editing.photos }}
          busy={saving}
          error={saveError}
          onClose={() => setEditing(null)}
          onSave={save}
          photoBusy={photoBusy}
          onSetCover={(photoId) => photoAction(
            () => api.post(`/staff/pets/${editing.pet.id}/photos/${photoId}/cover`))}
          onDeletePhoto={(photoId) => photoAction(
            () => api.delete(`/staff/pets/${editing.pet.id}/photos/${photoId}`))}
        />
      )}

      {addOpen && (
        <AddPetModal
          open
          busy={saving}
          error={saveError}
          onClose={() => setAddOpen(false)}
          onSave={save}
        />
      )}

      {deleting && (
        <DeletePetModal
          pet={deleting}
          busy={busyId === deleting.id}
          error={deleteError}
          onClose={() => setDeleting(null)}
          onDelete={() => removePet(deleting.id)}
          onUnavailable={() => {
            const p = deleting;
            setDeleting(null);
            changeStatus(p.id, "UNAVAILABLE", `${p.name} is off the catalogue`);
          }}
        />
      )}

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? "The change has been saved."}
      />
    </StaffLayout>
  );
}
