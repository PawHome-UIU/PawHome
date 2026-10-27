import { FiHeart, FiImage } from "react-icons/fi";
import Card from "../ui/Card";
import Button from "../ui/Button";
import StatusBadge from "../pets/StatusBadge";
import { API_BASE } from "../../lib/config";

/**
 * A pet card carrying its match score.
 *
 * The score is a percentage AND a sentence on the profile page -- never a bare
 * colour or ring. A recommendation a person cannot interrogate is one they
 * cannot trust, and "92%" with no "because" is exactly that.
 *
 * NO LOCAL `saved` STATE ANY MORE. It used to keep its own copy, which meant
 * the heart forgot on reload and two cards for the same pet could disagree.
 * The saved flag comes from the server on every row and the toggle is handed
 * up, so the page owns one truth and the card only draws it.
 *
 * THE MATCH BADGE HIDES WHEN matchPercent IS NULL, which is not the same as
 * hiding it at 0%. Null means there is nothing to compare -- an anonymous
 * viewer, or somebody who has not sat the personality quiz -- and "0% match"
 * would read as a judgement rather than an absence.
 */
export default function MatchCard({ pet, showMatch = true, onToggleSave, busy = false }) {
  const saved = pet.favorite ?? false;

  return (
    <Card className="flex h-full flex-col overflow-hidden">
      <div className="relative aspect-[4/3] bg-pw-sidebar/25 overflow-hidden shrink-0">
        {pet.coverPhotoPath ? (
          <img src={`${API_BASE}/files/${pet.coverPhotoPath}`} alt={pet.name}
            className="absolute inset-0 h-full w-full object-cover" />
        ) : (
          <span className="flex h-full w-full items-center justify-center text-pw-stroke">
            <FiImage className="h-8 w-8" />
          </span>
        )}
        {showMatch && pet.matchPercent != null && (
          <span className="absolute left-3 top-3 rounded-pill bg-pw-button px-2.5 py-1 text-[11px] font-semibold text-white">
            {pet.matchPercent}% match
          </span>
        )}
        <button
          type="button"
          onClick={() => onToggleSave?.(pet)}
          disabled={busy || !onToggleSave}
          aria-pressed={saved}
          aria-label={saved ? `Remove ${pet.name} from favorites` : `Save ${pet.name} to favorites`}
          className="absolute right-3 top-3 flex h-9 w-9 items-center justify-center rounded-full bg-white/95 text-pw-button shadow-card transition-transform hover:scale-105"
        >
          <FiHeart className={`h-4 w-4 ${saved ? "fill-pw-button" : ""}`} />
        </button>
      </div>

      <div className="flex flex-1 flex-col p-5">
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-lg font-semibold leading-tight">{pet.name}</h3>
          <StatusBadge status={pet.status} />
        </div>
        <p className="mt-1 text-sm text-pw-muted">
          {pet.breed} · {pet.ageValue} {pet.ageUnit?.toLowerCase()}
        </p>

        <div className="mt-3 flex flex-wrap gap-2">
          {(pet.traitLabels ?? []).map((t) => (
            <span key={t} className="rounded-pill bg-pw-input px-2.5 py-1 text-[11px] uppercase tracking-wide text-pw-body">
              {t}
            </span>
          ))}
        </div>

        <div className="mt-auto pt-5">
          <Button to={`/app/pets/${pet.id}`} full size="sm">View Profile</Button>
        </div>
      </div>
    </Card>
  );
}
