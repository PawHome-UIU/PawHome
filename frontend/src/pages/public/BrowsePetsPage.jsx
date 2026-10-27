import { useEffect, useMemo, useState } from "react";
import { FiSearch, FiCheck } from "react-icons/fi";
import PublicLayout from "../../components/layout/PublicLayout";
import PetCard from "../../components/pets/PetCard";
import Dropdown from "../../components/ui/Dropdown";
import CtaBand from "../../components/sections/CtaBand";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The guest catalogue, from GET /api/v1/pets.
 *
 * NO TOKEN NEEDED, and that is a SecurityConfig decision rather than an
 * accident: the browse endpoint is permitted to everyone by name, and it
 * answers with the trimmed PublicPetRow projection. A signed-in caller gets
 * match scores and hearts on the same route; a visitor gets neither, which is
 * why this page passes `favoritable` but sends them to /join.
 *
 * AVAILABLE ONLY, SERVER-SIDE. The endpoint already returns just the
 * adoptable pets, so the toggle below cannot widen the list -- it narrows what
 * came back. Household pets registered by their owners never appear here at
 * all: PetService joins shelters INNER, and those rows have no shelter.
 */
const SPECIES = ["Dog", "Cat", "Other"];
const AGES = ["Puppy / Kitten", "Young", "Adult", "Senior"];
const SIZES = ["Small", "Medium", "Large", "Giant"];
const GENDERS = ["Male", "Female"];

/** Roughly how old, in months, so the age bands can be compared. */
function months(value, unit) {
  if (value == null) return null;
  if (unit === "WEEKS") return value / 4.345;
  if (unit === "MONTHS") return value;
  return value * 12;
}

function inAgeBand(pet, band) {
  const m = months(pet.ageValue, pet.ageUnit);
  if (m == null) return false;
  if (band === "Puppy / Kitten") return m < 12;
  if (band === "Young") return m >= 12 && m < 36;
  if (band === "Adult") return m >= 36 && m < 96;
  return m >= 96;                                    // Senior
}

export default function BrowsePetsPage() {
  const [pets, setPets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState({});
  const [availableOnly, setAvailableOnly] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/pets");
        if (!cancelled) setPets(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the pets."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // The breed list is DERIVED, not a fixed array. A hard-coded one goes stale
  // the first time a shelter adds a breed nobody thought of, and then offers a
  // filter that matches nothing while hiding pets that do exist.
  const FILTERS = useMemo(() => ({
    Species: SPECIES,
    Breed: [...new Set(pets.map((p) => p.breed).filter(Boolean))].sort(),
    Age: AGES,
    Size: SIZES,
    Gender: GENDERS,
  }), [pets]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    return pets.filter((p) => {
      if (availableOnly && p.status !== "AVAILABLE") return false;
      if (q && !`${p.name} ${p.breed}`.toLowerCase().includes(q)) return false;
      if (picked.Species && p.species !== picked.Species.toUpperCase()) return false;
      if (picked.Breed && p.breed !== picked.Breed) return false;
      if (picked.Age && !inAgeBand(p, picked.Age)) return false;
      if (picked.Size && p.size !== picked.Size.toUpperCase()) return false;
      if (picked.Gender && p.sex !== picked.Gender.toUpperCase()) return false;
      return true;
    });
  }, [pets, query, picked, availableOnly]);

  return (
    <PublicLayout>
      <section className="mx-auto max-w-6xl px-5 pb-8 pt-12 sm:px-8">
        <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">Meet our pets</h1>
        <p className="mt-3 text-[15px] text-pw-body">
          Browse available pets — sign up to adopt or save your favorites.
        </p>

        <div className="relative mt-8 max-w-xl">
          <FiSearch className="pointer-events-none absolute left-5 top-1/2 h-5 w-5 -translate-y-1/2 text-pw-muted" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by name, breed…"
            aria-label="Search pets by name or breed"
            className="h-14 w-full rounded-pill border border-pw-stroke/60 bg-white pl-14 pr-5 text-[15px] text-pw-ink placeholder:text-pw-muted focus:border-pw-button focus:outline-none focus:ring-2 focus:ring-pw-button/25"
          />
        </div>

        <div className="mt-6 flex flex-wrap items-center gap-3">
          {Object.entries(FILTERS).map(([label, options]) => (
            <Dropdown
              key={label}
              label={label}
              options={options}
              value={picked[label]}
              anyLabel={`Any ${label.toLowerCase()}`}
              onChange={(v) => setPicked((p) => ({ ...p, [label]: v }))}
            />
          ))}

          <span className="mx-1 hidden h-6 w-px bg-pw-stroke/60 sm:block" />

          <button
            type="button"
            onClick={() => setAvailableOnly((v) => !v)}
            aria-pressed={availableOnly}
            className={[
              "inline-flex items-center gap-2 rounded-pill border px-4 py-2 text-sm transition-colors",
              availableOnly
                ? "border-pw-button bg-pw-input text-pw-button"
                : "border-pw-stroke/60 bg-pw-input/60 text-pw-body",
            ].join(" ")}
          >
            Available only
            <span className={[
              "flex h-5 w-5 items-center justify-center rounded-full",
              availableOnly ? "bg-pw-button text-white" : "border border-pw-stroke bg-white",
            ].join(" ")}>
              {availableOnly && <FiCheck className="h-3 w-3" />}
            </span>
          </button>
        </div>

        <p className="mt-6 text-sm text-pw-muted">
          {loading
            ? "Loading…"
            : `${results.length} ${results.length === 1 ? "pet" : "pets"} found`}
        </p>

        {loadError ? (
          <div className="mt-6 rounded-card border border-dashed border-pw-stroke bg-white px-6 py-16 text-center">
            <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          </div>
        ) : results.length > 0 ? (
          <div className="mt-6 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {results.map((pet) => (
              <PetCard key={pet.id} pet={pet} favoritable />
            ))}
          </div>
        ) : (
          <div className="mt-6 rounded-card border border-dashed border-pw-stroke bg-white px-6 py-16 text-center">
            <p className="text-[15px] font-medium text-pw-ink">No pets match those filters.</p>
            <p className="mt-1.5 text-sm text-pw-muted">
              {loading
                ? "Fetching the catalogue…"
                : "Try clearing a filter, or turn off “Available only” to include pending adoptions."}
            </p>
          </div>
        )}
      </section>

      <section className="mx-auto max-w-6xl px-5 py-12 sm:px-8">
        <div className="rounded-card bg-pw-sidebar px-8 py-10 sm:px-12">
          <div className="flex flex-wrap items-center justify-between gap-6">
            <div>
              <h2 className="text-2xl font-semibold text-white sm:text-3xl">Ready to adopt?</h2>
              <p className="mt-2 max-w-md text-[15px] leading-relaxed text-white/85">
                Create an account to apply, save favorites, and get matched with
                the perfect furry friend for your home.
              </p>
            </div>
            <a href="/join"
              className="rounded-pill bg-white px-10 py-4 text-lg font-medium text-pw-button shadow-card hover:bg-pw-input">
              Sign up now
            </a>
          </div>
        </div>
      </section>

      {/* THE PAGER IS GONE, not disabled. It was drawn from the design with
          pages 1-8 hard-coded, and now that the grid holds every pet the
          endpoint returns, offering a page 8 that does not exist would be the
          page lying about how much it is showing. When GET /pets paginates,
          this comes back driven by the response. */}
    </PublicLayout>
  );
}
