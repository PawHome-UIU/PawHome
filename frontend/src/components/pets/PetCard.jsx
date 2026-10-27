import { useState } from "react";
import { FiHeart, FiImage } from "react-icons/fi";
import Card from "../ui/Card";
import Button from "../ui/Button";
import StatusBadge from "./StatusBadge";
import { petAge } from "../../lib/petFormat";
import { API_BASE } from "../../lib/config";

/**
 * One pet, one card. Used by the landing page and the guest browse grid.
 *
 * IT SHOWS THE REAL PHOTO. It used to draw a tinted block unconditionally,
 * which meant a shelter could upload a full set of pictures and the visitors
 * who see this page first would never know: the placeholder looked identical
 * whether photos existed or not. The block is now the fallback for a pet with
 * no pictures, and nothing else.
 *
 * `favoritable` is off by default because a guest has nowhere to save to --
 * showing a heart that silently does nothing is worse than not showing one.
 */
export default function PetCard({ pet, favoritable = false, onFavorite }) {
  const [saved, setSaved] = useState(false);

  function toggle() {
    setSaved((v) => !v);
    onFavorite?.(pet, !saved);
  }

  const traits = pet.traitLabels ?? [];

  return (
    <Card className="flex h-full flex-col overflow-hidden">
      <div className="relative aspect-[4/3] bg-pw-sidebar/25 overflow-hidden shrink-0">
        {pet.coverPhotoPath ? (
          <img src={`${API_BASE}/files/${pet.coverPhotoPath}`} alt={pet.name}
            loading="lazy" className="absolute inset-0 h-full w-full object-cover" />
        ) : (
          <span className="flex h-full w-full items-center justify-center">
            <FiImage className="h-7 w-7 text-pw-stroke" />
          </span>
        )}

        {favoritable && (
          <button
            type="button"
            onClick={toggle}
            aria-label={saved ? `Remove ${pet.name} from favourites` : `Save ${pet.name} to favourites`}
            aria-pressed={saved}
            className="absolute right-3 top-3 flex h-9 w-9 items-center justify-center rounded-full bg-white/95 text-pw-button shadow-card transition-transform hover:scale-105"
          >
            <FiHeart className={`h-4 w-4 ${saved ? "fill-pw-button" : ""}`} />
          </button>
        )}
      </div>

      <div className="flex flex-1 flex-col p-5">
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-lg font-semibold leading-tight">{pet.name}</h3>
          <StatusBadge status={pet.status} />
        </div>

        <p className="mt-1 text-sm text-pw-muted">
          {pet.breed} • {petAge(pet.ageValue, pet.ageUnit)}
        </p>

        <div className="mt-3 flex flex-wrap gap-2">
          {traits.map((t) => (
            <span key={t} className="rounded-pill bg-pw-input px-2.5 py-1 text-xs text-pw-body">
              {t}
            </span>
          ))}
        </div>

        {/* The wrapper carries mt-auto, not the Button. Putting both mt-auto
            and a margin utility on the same element loses: the later class
            wins and the button stops sitting on the row's baseline when one
            card has an extra trait chip. */}
        <div className="mt-auto pt-5">
          <Button to={`/pets/${pet.id}`} full size="sm">
            View Profile
          </Button>
        </div>
      </div>
    </Card>
  );
}
