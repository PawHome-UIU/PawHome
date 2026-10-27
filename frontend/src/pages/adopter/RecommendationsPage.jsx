import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { FiZap, FiCheck, FiX, FiImage, FiRefreshCw } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import MatchCard from "../../components/adopter/MatchCard";
import EligibilityQuizModal from "../../components/adopter/EligibilityQuizModal";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * Personality matches.
 *
 * THE WHOLE PAGE DEPENDS ON ONE THING HAVING HAPPENED: a personality attempt.
 * Without it every pet scores null, and a grid of unscored cards under the
 * heading "Your personality matches" is a page that has quietly failed. So the
 * no-attempt case is not an empty state tacked on the end -- it replaces the
 * page, and asks for the quiz.
 *
 * NULL IS NOT ZERO, and the ranking respects that: a pet with no traits mapped
 * at all scores 0% honestly (it shares none of yours), while a viewer with no
 * attempt gets no numbers whatsoever. Sorting unscored pets as if they were 0%
 * would bury them under pets that genuinely mismatch.
 *
 * NO SEPARATE RECOMMENDATIONS ENDPOINT. This is GET /api/v1/pets ordered by the
 * match the server already computes. A second endpoint returning "the good
 * ones" would be the same query with a threshold nobody agreed on, and it
 * would drift from the percentages the browse page shows for the same animals.
 */
export default function RecommendationsPage() {
  const [pets, setPets] = useState([]);
  const [traits, setTraits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [savingId, setSavingId] = useState(null);
  const [quizOpen, setQuizOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [petRes, traitRes] = await Promise.all([
        api.get("/pets"),
        api.get("/traits/mine"),
      ]);
      setPets(petRes.data);
      setTraits(traitRes.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load your matches."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [petRes, traitRes] = await Promise.all([
          api.get("/pets"),
          api.get("/traits/mine"),
        ]);
        if (cancelled) return;
        setPets(petRes.data);
        setTraits(traitRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load your matches."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  async function toggleSave(pet) {
    setSavingId(pet.id);
    const next = !pet.favorite;
    try {
      if (next) await api.put(`/favorites/${pet.id}`);
      else await api.delete(`/favorites/${pet.id}`);
      setPets((list) => list.map((p) => (p.id === pet.id ? { ...p, favorite: next } : p)));
    } catch {
      // Quiet: the heart stays as the server last reported it.
    } finally {
      setSavingId(null);
    }
  }

  // Scored pets only, best first. An unscored one on this page is noise --
  // there is nothing to recommend it BY.
  const ranked = useMemo(
    () => pets.filter((p) => p.matchPercent != null)
              .sort((a, b) => b.matchPercent - a.matchPercent),
    [pets]);

  const top = ranked.slice(0, 2);
  const rest = ranked.slice(2);
  const hasQuiz = traits.length > 0;

  if (loading) {
    return (
      <AdopterLayout>
        <Card className="px-6 py-20 text-center">
          <p className="text-[15px] text-pw-muted">Working out your matches…</p>
        </Card>
      </AdopterLayout>
    );
  }

  if (loadError) {
    return (
      <AdopterLayout>
        <Card className="px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      </AdopterLayout>
    );
  }

  /* No attempt, no matches. Replacing the page rather than showing empty
     sections: everything below the fold is derived from the quiz. */
  if (!hasQuiz) {
    return (
      <AdopterLayout>
        <Card tinted className="mx-auto max-w-2xl p-10 text-center">
          <span className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-white text-pw-button">
            <FiZap className="h-7 w-7" />
          </span>
          <h1 className="mt-6 text-3xl font-semibold text-pw-button">
            Take the personality quiz
          </h1>
          <p className="mt-4 text-[15px] leading-relaxed text-pw-body">
            Four questions. Your answers map onto the same temperament traits a
            shelter records for every animal, so each pet can tell you which of
            your traits it shares and which it does not.
          </p>
          <Button size="lg" className="mt-8" onClick={() => setQuizOpen(true)}>
            Start the quiz
          </Button>
          <p className="mt-4 text-sm text-pw-muted">
            It is not scored — there is no passing or failing.
          </p>
        </Card>

        {quizOpen && (
          <EligibilityQuizModal
            open
            quizType="PERSONALITY"
            onClose={() => setQuizOpen(false)}
            onComplete={() => { setQuizOpen(false); load(); }}
          />
        )}
      </AdopterLayout>
    );
  }

  return (
    <AdopterLayout>
      <div className="grid gap-5 lg:grid-cols-3">
        <Card tinted className="p-7 lg:col-span-2">
          <h1 className="text-3xl font-semibold text-pw-button">Your personality matches</h1>
          <p className="mt-3 max-w-xl text-[15px] leading-relaxed text-pw-body">
            Your quiz answers map onto the same temperament traits a shelter
            records for every animal — so every score below can tell you which
            traits matched and which did not.
          </p>
        </Card>

        <Card className="p-6">
          <div className="flex items-center justify-between">
            <h2 className="text-xs font-semibold uppercase tracking-wider text-pw-muted">Your traits</h2>
            {/* The quiz is a modal, not a page: it has no URL of its own, so
                this is a button rather than a Link to a route that would 404. */}
            <button
              type="button"
              onClick={() => setQuizOpen(true)}
              className="text-sm font-medium text-pw-button hover:underline"
            >
              Retake Quiz
            </button>
          </div>
          <div className="mt-4 flex flex-wrap gap-2">
            {traits.map((t) => (
              <span key={t} className="inline-flex items-center gap-1.5 rounded-pill bg-pw-input px-3 py-1.5 text-sm text-pw-button">
                <FiZap className="h-3.5 w-3.5" /> {t}
              </span>
            ))}
          </div>
          <p className="mt-4 text-xs leading-relaxed text-pw-muted">
            Derived from your most recent personality quiz. Retaking it replaces
            this set — earlier attempts are kept.
          </p>
        </Card>
      </div>

      {ranked.length === 0 ? (
        <Card className="mt-10 px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-ink">
            No pets are listed yet.
          </p>
          <p className="mt-1.5 text-sm text-pw-muted">
            Your traits are saved — matches will appear as shelters add animals.
          </p>
          <Button to="/app/pets" variant="outline" className="mt-6">Browse anyway</Button>
        </Card>
      ) : (
        <>
          <section className="mt-10">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-2xl font-semibold">Top Recommended for You</h2>
                <p className="mt-1.5 text-[15px] text-pw-muted">
                  The closest fits to your temperament profile.
                </p>
              </div>
              <Button variant="outline" size="sm" onClick={load}>
                <FiRefreshCw className="h-4 w-4" /> Refresh
              </Button>
            </div>

            <div className="mt-6 grid gap-6 sm:grid-cols-2">
              {top.map((p) => (
                <Card key={p.id} className="flex flex-col overflow-hidden sm:flex-row">
                  <div className="relative aspect-[4/3] shrink-0 bg-pw-sidebar/25 sm:aspect-auto sm:w-2/5 overflow-hidden">
                    {p.coverPhotoPath ? (
                      <img src={`${API_BASE}/files/${p.coverPhotoPath}`} alt={p.name}
                        className="absolute inset-0 h-full w-full object-cover" />
                    ) : (
                      <span className="flex h-full w-full items-center justify-center text-pw-stroke">
                        <FiImage className="h-7 w-7" />
                      </span>
                    )}
                    <span className="absolute left-3 top-3 rounded-pill bg-pw-button px-2.5 py-1 text-[11px] font-semibold text-white">
                      {p.matchPercent}% match
                    </span>
                  </div>
                  <div className="flex flex-1 flex-col p-5">
                    <h3 className="text-xl font-semibold">{p.name}</h3>
                    <p className="mt-1 text-sm text-pw-muted">
                      {p.breed} · {p.ageValue} {p.ageUnit.toLowerCase()}
                    </p>

                    {/* The "because". A percentage on its own is a number to
                        take on faith; the two lists are what make it a
                        recommendation. Both come from the server, so they
                        cannot disagree with the score beside them. */}
                    <ul className="mt-4 space-y-1.5">
                      {p.matchedTraits.map((t) => (
                        <li key={t} className="flex items-center gap-2 text-sm text-pw-body">
                          <FiCheck className="h-4 w-4 shrink-0 text-green-600" /> Matched on {t}
                        </li>
                      ))}
                      {p.missedTraits.map((t) => (
                        <li key={t} className="flex items-center gap-2 text-sm text-pw-muted">
                          <FiX className="h-4 w-4 shrink-0 text-pw-muted" /> Did not match {t}
                        </li>
                      ))}
                    </ul>

                    <Link to={`/app/pets/${p.id}`}
                      className="mt-auto pt-5 text-sm font-medium text-pw-button hover:underline">
                      View Profile →
                    </Link>
                  </div>
                </Card>
              ))}
            </div>
          </section>

          {rest.length > 0 && (
            <section className="mt-10">
              <h2 className="text-xs font-semibold uppercase tracking-wider text-pw-muted">
                More pets you may like
              </h2>
              <div className="mt-5 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
                {rest.map((p) => (
                  <MatchCard
                    key={p.id}
                    pet={p}
                    busy={savingId === p.id}
                    onToggleSave={toggleSave}
                  />
                ))}
              </div>
            </section>
          )}
        </>
      )}

      {quizOpen && (
        <EligibilityQuizModal
          open
          quizType="PERSONALITY"
          onClose={() => setQuizOpen(false)}
          onComplete={() => { setQuizOpen(false); load(); }}
        />
      )}
    </AdopterLayout>
  );
}
