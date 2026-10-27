import { useCallback, useEffect, useMemo, useState } from "react";
import { FiHeart, FiRefreshCw } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import MatchCard from "../../components/adopter/MatchCard";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Dropdown from "../../components/ui/Dropdown";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * Saved pets.
 *
 * ADOPTED AND UNAVAILABLE PETS STAY IN THE LIST. Somebody who saved an animal
 * should be told it has been adopted, not quietly shown a shorter list and
 * left wondering -- the card carries a status badge that says so plainly.
 *
 * UN-HEARTING REALLY DELETES. favorites is the one table in this system where
 * a hard delete is right: the row is (user_id, pet_id) and nothing else, so
 * there is no history in it to protect. Everywhere else -- pets, applications,
 * quiz questions -- a record is evidence of something that happened and gets
 * archived instead.
 */
export default function FavoritesPage() {
  const [pets, setPets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [busyId, setBusyId] = useState(null);
  const [sort, setSort] = useState("Recently Saved");

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/favorites");
      setPets(data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your favourites."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/favorites");
        if (!cancelled) setPets(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your favourites."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /**
   * Un-heart.
   *
   * The row leaves the list immediately rather than after a reload: on THIS
   * page the heart is the only reason a pet is here, so leaving it sitting
   * there greyed out would be stranger than removing it. It comes back on
   * refresh if the delete actually failed.
   */
  async function unsave(pet) {
    setBusyId(pet.id);
    try {
      await api.delete(`/favorites/${pet.id}`);
      setPets((list) => list.filter((p) => p.id !== pet.id));
    } catch (err) {
      setLoadError(apiError(err, "That could not be removed."));
    } finally {
      setBusyId(null);
    }
  }

  const shown = useMemo(() => {
    if (sort === "Name A–Z") return [...pets].sort((a, b) => a.name.localeCompare(b.name));
    if (sort === "Match score") {
      // Unscored last, not as zero -- null means nothing to compare.
      return [...pets].sort((a, b) => (b.matchPercent ?? -1) - (a.matchPercent ?? -1));
    }
    // "Recently Saved" is the order the server returns (favorites.created_at
    // descending), so it needs no client-side sort at all.
    return pets;
  }, [pets, sort]);

  return (
    <AdopterLayout>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-4xl font-semibold tracking-tight">Your Favorites</h1>
          <p className="mt-2 flex items-center gap-2 text-[15px] text-pw-body">
            <FiHeart className="h-4 w-4 fill-pw-button text-pw-button" />
            {loading ? "Loading…"
              : `${pets.length} saved ${pets.length === 1 ? "pet" : "pets"}`}
          </p>
        </div>

        <div className="flex items-center gap-2.5 text-sm text-pw-muted">
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          Sort by:
          <Dropdown
            variant="bar" align="right" label="Recently Saved"
            value={sort} onChange={(v) => setSort(v ?? "Recently Saved")}
            options={["Recently Saved", "Name A–Z", "Match score"]}
            buttonClassName="h-11"
          />
        </div>
      </div>

      {loadError ? (
        <Card className="mt-8 px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      ) : loading ? (
        <Card className="mt-8 px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </Card>
      ) : pets.length === 0 ? (
        <Card className="mt-8 px-6 py-16 text-center">
          <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-pw-input text-pw-button">
            <FiHeart className="h-6 w-6" />
          </span>
          <p className="mt-5 text-[15px] font-medium text-pw-ink">Nothing saved yet.</p>
          <p className="mt-1.5 text-sm text-pw-muted">
            Tap the heart on any pet and they will wait for you here.
          </p>
          <Button to="/app/pets" className="mt-6">Browse pets</Button>
        </Card>
      ) : (
        <div className="mt-8 grid gap-6 sm:grid-cols-2 xl:grid-cols-4">
          {shown.map((pet) => (
            <MatchCard
              key={pet.id}
              pet={pet}
              busy={busyId === pet.id}
              onToggleSave={unsave}
            />
          ))}
        </div>
      )}
    </AdopterLayout>
  );
}
