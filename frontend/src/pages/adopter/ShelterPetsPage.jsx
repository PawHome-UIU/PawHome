import { useCallback, useEffect, useMemo, useState } from "react";
import { FiSearch, FiCheck, FiRefreshCw } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import MatchCard from "../../components/adopter/MatchCard";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Dropdown from "../../components/ui/Dropdown";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The signed-in catalogue: the same pets a guest sees at /pets, inside the
 * portal chrome.
 *
 * ONE ENDPOINT SERVES BOTH, and that is why this page is not a fork of the
 * guest browse. GET /api/v1/pets answers differently depending on who asks:
 * a guest gets pets, a signed-in caller gets the same pets carrying their own
 * saved flag and a match percentage. Two endpoints would have drifted the
 * first time somebody added a field to one of them.
 *
 * THE FILTERS ARE CLIENT-SIDE OVER THE LOADED LIST, and that is a deliberate
 * limit rather than an oversight: the server already narrows to AVAILABLE
 * pets, and until a shelter network is large enough to paginate, filtering
 * twenty cards in the browser beats a round trip per dropdown. Species is the
 * exception -- it is a real query parameter, because it is the one the
 * idx_pets_browse index was built for.
 */
const FILTERS = {
  Species: ["Dog", "Cat", "Other"],
  Size: ["Small", "Medium", "Large", "Giant"],
  Gender: ["Male", "Female"],
};

export default function ShelterPetsPage() {
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState({});
  const [availableOnly, setAvailableOnly] = useState(true);
  const [sort, setSort] = useState("match");

  const [pets, setPets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [savingId, setSavingId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/pets");
      setPets(data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load the pets."));
    } finally {
      setLoading(false);
    }
  }, []);

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

  /**
   * Save or unsave.
   *
   * PUT and DELETE rather than a toggle endpoint, and the row is patched in
   * place rather than reloading the whole grid -- a heart that makes the page
   * flicker is a heart people stop pressing. On failure the flag is left as
   * the server last reported it, so the UI never claims a save that did not
   * happen.
   */
  async function toggleSave(pet) {
    setSavingId(pet.id);
    const next = !pet.favorite;
    try {
      if (next) await api.put(`/favorites/${pet.id}`);
      else await api.delete(`/favorites/${pet.id}`);
      setPets((list) => list.map((p) => (p.id === pet.id ? { ...p, favorite: next } : p)));
    } catch {
      // Deliberately quiet. A failed bookmark is not worth a modal; the heart
      // simply stays as it was, which is the truth.
    } finally {
      setSavingId(null);
    }
  }

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    const rows = pets.filter((p) => {
      if (availableOnly && p.status !== "AVAILABLE") return false;
      if (q && !`${p.name} ${p.breed}`.toLowerCase().includes(q)) return false;
      if (picked.Species && p.species !== picked.Species.toUpperCase()) return false;
      if (picked.Size && p.size !== picked.Size.toUpperCase()) return false;
      if (picked.Gender && p.sex !== picked.Gender.toUpperCase()) return false;
      return true;
    });

    // Unscored pets sort last under "Best match" rather than being read as 0%
    // -- null means "nothing to compare", which is not the same as a bad match.
    return sort === "match"
      ? [...rows].sort((a, b) => (b.matchPercent ?? -1) - (a.matchPercent ?? -1))
      : [...rows].sort((a, b) => a.name.localeCompare(b.name));
  }, [pets, query, picked, availableOnly, sort]);

  const activeFilters = Object.values(picked).filter(Boolean).length;
  const scored = pets.some((p) => p.matchPercent != null);

  return (
    <AdopterLayout
      title="Shelter Pets"
      subtitle="Every pet currently listed across our partner shelters."
    >
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative min-w-[260px] flex-1 md:max-w-xl">
          <FiSearch className="pointer-events-none absolute left-5 top-1/2 h-5 w-5 -translate-y-1/2 text-pw-muted" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by name, breed…"
            aria-label="Search pets by name or breed"
            className="w-full rounded-pill border border-pw-stroke/60 bg-white py-3.5 pl-14 pr-5 text-[15px] text-pw-ink placeholder:text-pw-muted focus:border-pw-button focus:outline-none focus:ring-2 focus:ring-pw-button/25"
          />
        </div>
        <Button variant="outline" size="sm" onClick={load} disabled={loading}>
          <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
        </Button>
      </div>

      <div className="mt-5 flex flex-wrap items-center gap-3">
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

        <button
          type="button"
          onClick={() => setAvailableOnly((v) => !v)}
          aria-pressed={availableOnly}
          className={[
            "inline-flex items-center gap-2 rounded-pill border px-4 py-2 text-sm transition-colors",
            availableOnly
              ? "border-pw-button bg-pw-input text-pw-button"
              : "border-pw-stroke/60 bg-white text-pw-body",
          ].join(" ")}
        >
          Available only
          <span
            className={[
              "flex h-5 w-5 items-center justify-center rounded-full",
              availableOnly ? "bg-pw-button text-white" : "border border-pw-stroke bg-white",
            ].join(" ")}
          >
            {availableOnly && <FiCheck className="h-3 w-3" />}
          </span>
        </button>

        {activeFilters > 0 && (
          <button
            type="button"
            onClick={() => setPicked({})}
            className="text-sm font-medium text-pw-button hover:underline"
          >
            Clear {activeFilters} filter{activeFilters === 1 ? "" : "s"}
          </button>
        )}
      </div>

      <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-pw-muted">
          {loading ? "Loading…"
            : `${results.length} ${results.length === 1 ? "pet" : "pets"} found`}
        </p>
        <div className="flex items-center gap-2 text-sm text-pw-muted">
          Sort by
          <Dropdown
            variant="bar"
            align="right"
            label="Best match"
            value={sort}
            onChange={(v) => setSort(v ?? "match")}
            options={[
              { value: "match", label: "Best match" },
              { value: "name", label: "Name (A–Z)" },
            ]}
          />
        </div>
      </div>

      {/* Said once, not on every card. A grid of pets with no badges is
          confusing until you know why; after the quiz the badges explain
          themselves. */}
      {!loading && !loadError && pets.length > 0 && !scored && (
        <p className="mt-4 rounded-card bg-pw-input px-5 py-3.5 text-sm text-pw-body">
          Take the personality quiz and these cards will show how well each pet
          matches you.
        </p>
      )}

      {loadError ? (
        <Card className="mt-5 px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      ) : loading ? (
        <Card className="mt-5 px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading pets…</p>
        </Card>
      ) : results.length > 0 ? (
        <div className="mt-5 grid gap-6 sm:grid-cols-2 xl:grid-cols-4">
          {results.map((pet) => (
            <MatchCard
              key={pet.id}
              pet={pet}
              busy={savingId === pet.id}
              onToggleSave={toggleSave}
            />
          ))}
        </div>
      ) : (
        <div className="mt-5 rounded-card border border-dashed border-pw-stroke bg-white px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-ink">
            {pets.length === 0 ? "No pets are listed yet." : "No pets match those filters."}
          </p>
          <p className="mt-1.5 text-sm text-pw-muted">
            {pets.length === 0
              ? "Check back soon — shelters add animals as they arrive."
              : "Try clearing a filter, or turn off “Available only” to include pending adoptions."}
          </p>
        </div>
      )}
    </AdopterLayout>
  );
}
