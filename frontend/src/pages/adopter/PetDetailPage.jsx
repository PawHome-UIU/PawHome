import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  FiArrowLeft, FiHeart, FiShield, FiCheckCircle, FiMapPin, FiZap, FiX, FiImage,
} from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import StatusBadge from "../../components/pets/StatusBadge";
import MatchCard from "../../components/adopter/MatchCard";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { API_BASE } from "../../lib/config";

/**
 * One pet.
 *
 * THE MATCH SCORE ALWAYS SHIPS WITH ITS REASONS. The server returns which of
 * the viewer's traits this pet has and which it does not, so the panel can say
 * "matched on Calm, did not match Energetic" rather than a bare percentage. A
 * recommendation somebody cannot interrogate is one they cannot trust.
 *
 * NO VIEW COUNT SENTENCE. The design had "N people have viewed this pet this
 * week"; the column exists but nothing increments it, and PublicPetRow does
 * not carry it -- an applicant seeing how many rivals a pet has is a fact the
 * shelter did not choose to publish. It stays out until there is a decision
 * about that, rather than shipping a fabricated number.
 */
export default function PetDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const [p, setP] = useState(null);
  const [others, setOthers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [saving, setSaving] = useState(false);
  const [active, setActive] = useState(0);
  const [health, setHealth] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [one, all, vax] = await Promise.all([
          api.get(`/pets/${id}`),
          api.get("/pets"),
          // Its own call, and allowed to fail on its own: a pet whose health
          // record this caller may not read must still show the rest of the
          // profile rather than an error page.
          api.get(`/pets/${id}/health`).catch(() => null),
        ]);
        if (cancelled) return;
        setP(one.data);
        setHealth(vax?.data ?? null);
        setActive(0);
        // "You might also like" is the rest of the catalogue, best match first.
        // Not a similarity engine -- calling it one would be a claim the code
        // does not support.
        setOthers(all.data
          .filter((x) => String(x.id) !== String(id))
          .sort((a, b) => (b.matchPercent ?? -1) - (a.matchPercent ?? -1))
          .slice(0, 3));
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load that pet."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  async function toggleSave() {
    setSaving(true);
    const next = !p.favorite;
    try {
      if (next) await api.put(`/favorites/${p.id}`);
      else await api.delete(`/favorites/${p.id}`);
      setP((x) => ({ ...x, favorite: next }));
    } catch {
      // Quiet on purpose: the heart stays as the server last reported it.
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <AdopterLayout>
        <Card className="px-6 py-20 text-center">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </Card>
      </AdopterLayout>
    );
  }

  if (loadError || !p) {
    return (
      <AdopterLayout>
        <Card className="px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-danger">
            {loadError || "That pet could not be found."}
          </p>
          <Button to="/app/pets" className="mt-6">Browse pets</Button>
        </Card>
      </AdopterLayout>
    );
  }

  const canApply = p.status === "AVAILABLE";
  const photos = p.photoPaths?.length ? p.photoPaths : [];
  const hero = photos[active] ?? p.coverPhotoPath;
  const age = `${p.ageValue} ${p.ageUnit.toLowerCase()}`;
  const nice = (v) => v.charAt(0) + v.slice(1).toLowerCase();

  return (
    <AdopterLayout>
      <button onClick={() => navigate(-1)}
        className="mb-5 inline-flex items-center gap-2 text-2xl font-semibold text-pw-ink hover:text-pw-button">
        <FiArrowLeft className="h-5 w-5 text-pw-button" /> {p.name}
      </button>

      <div className="grid gap-8 lg:grid-cols-2">
        <div>
          <div className="relative aspect-[4/3] overflow-hidden rounded-card bg-pw-sidebar/25 shrink-0">
            {hero ? (
              <img src={`${API_BASE}/files/${hero}`} alt={p.name}
                className="absolute inset-0 h-full w-full object-cover" />
            ) : (
              <span className="flex h-full w-full items-center justify-center text-pw-stroke">
                <FiImage className="h-10 w-10" />
              </span>
            )}
          </div>

          {/* Only the photos this pet actually has. The four fixed placeholder
              tiles implied a gallery that was never there. */}
          {photos.length > 1 && (
            <div className="mt-3 flex flex-wrap gap-3">
              {photos.map((path, i) => (
                <button key={path} onClick={() => setActive(i)}
                  aria-label={`Photo ${i + 1} of ${photos.length}`} aria-pressed={active === i}
                  className={`h-20 w-20 overflow-hidden rounded-2xl bg-pw-sidebar/25 ${
                    active === i ? "ring-2 ring-pw-button ring-offset-2" : ""}`}>
                  <img src={`${API_BASE}/files/${path}`} alt=""
                    className="h-full w-full object-cover" />
                </button>
              ))}
            </div>
          )}
        </div>

        <Card className="flex h-fit flex-col p-7">
          <div className="flex items-start justify-between gap-3">
            <StatusBadge status={p.status} />
            <button type="button" onClick={toggleSave} disabled={saving}
              aria-pressed={p.favorite}
              aria-label={p.favorite ? `Remove ${p.name} from favorites` : `Save ${p.name} to favorites`}
              className="flex h-10 w-10 items-center justify-center rounded-full border border-pw-stroke/60 text-pw-button hover:bg-pw-input disabled:opacity-50">
              <FiHeart className={`h-4 w-4 ${p.favorite ? "fill-pw-button" : ""}`} />
            </button>
          </div>

          <h1 className="mt-4 text-4xl font-semibold tracking-tight">{p.name}</h1>
          <p className="mt-1.5 text-[15px] text-pw-body">
            {p.breed} · {nice(p.sex)} · {age}
          </p>

          <div className="mt-6 grid grid-cols-2 gap-4">
            {[["Size", nice(p.size)], ["Shelter", p.shelterName]].map(([k, v]) => (
              <div key={k} className="rounded-2xl bg-pw-input p-4">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-pw-muted">{k}</p>
                <p className="mt-1 text-[15px] font-medium text-pw-ink">{v}</p>
              </div>
            ))}
          </div>

          {p.matchPercent != null && (
            <div className="mt-5 rounded-2xl border border-pw-button/30 bg-pw-input/70 p-4">
              <p className="flex items-center gap-2 text-[15px] font-semibold text-pw-button">
                <FiZap className="h-4 w-4" /> {p.matchPercent}% match with you
              </p>
              {/* The score always ships with its reasons. */}
              <ul className="mt-2.5 space-y-1">
                {p.matchedTraits.map((t) => (
                  <li key={t} className="flex items-center gap-2 text-sm text-pw-body">
                    <FiCheckCircle className="h-3.5 w-3.5 shrink-0 text-green-600" /> Matched on {t}
                  </li>
                ))}
                {p.missedTraits.map((t) => (
                  <li key={t} className="flex items-center gap-2 text-sm text-pw-muted">
                    <FiX className="h-3.5 w-3.5 shrink-0" /> Did not match {t}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {p.traitLabels.length > 0 && (
            <>
              <p className="mt-6 text-[11px] font-semibold uppercase tracking-wider text-pw-muted">
                Personality Traits
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                {p.traitLabels.map((t) => (
                  <span key={t} className="rounded-pill bg-pw-input px-3.5 py-1.5 text-sm text-pw-body">{t}</span>
                ))}
              </div>
            </>
          )}

          <Button to={`/app/pets/${p.id}/apply`} size="lg" full className="mt-7" disabled={!canApply}>
            {canApply ? "Apply to Adopt"
              : p.status === "PENDING" ? "An application is already in review"
              : `${p.name} is no longer available`}
          </Button>

          {p.adoptionFee != null && Number(p.adoptionFee) > 0 && (
            <p className="mt-3 text-center text-sm text-pw-muted">
              Adoption fee ৳{Number(p.adoptionFee).toFixed(2)}
            </p>
          )}
        </Card>
      </div>

      <div className="mt-10 grid gap-8 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <h2 className="text-2xl font-semibold">About {p.name}</h2>
          <p className="mt-4 whitespace-pre-line text-[15px] leading-relaxed text-pw-body">
            {p.description || `${p.shelterName} has not written a description for ${p.name} yet.`}
          </p>
        </div>

        <div className="space-y-5">
          <Card className="p-6">
            <h3 className="flex items-center gap-2 text-[15px] font-semibold text-pw-button">
              <FiShield className="h-4 w-4" /> Health &amp; Vetting
            </h3>
            {/* VACCINATED IS NOW A REAL ANSWER, and it is computed the same
                way v_pet_listing computes is_vaccinated -- at least one
                completed shot and nothing scheduled past its due date -- so
                the tick here and the tick on a listing cannot disagree.
                Rendered only when the record loaded: a grey cross for a
                record we could not read would say "not vaccinated", which is
                the false tick V1's comment warned about, pointing the other
                way. */}
            <ul className="mt-4 space-y-2.5">
              {[
                ...(health ? [["Vaccinated", health.vaccinated]] : []),
                ["Neutered", p.neutered],
                ["Microchipped", p.microchipped],
              ].map(([label, ok]) => (
                <li key={label} className="flex items-center gap-2.5 text-[15px] text-pw-body">
                  {ok ? <FiCheckCircle className="h-4 w-4 shrink-0 text-green-600" />
                      : <FiX className="h-4 w-4 shrink-0 text-pw-muted" />}
                  {label}
                </li>
              ))}
            </ul>

            {health && health.vaccinations.length > 0 ? (
              <ul className="mt-4 space-y-2 border-t border-pw-stroke/40 pt-3">
                {health.vaccinations.map((v) => (
                  <li key={v.id} className="text-xs leading-relaxed">
                    <span className="font-medium text-pw-ink">{v.vaccineName}</span>
                    <span className="text-pw-muted">
                      {" "}· {v.status === "SCHEDULED" ? "due " : "given "}
                      {v.status === "SCHEDULED" ? v.nextDueOn : v.administeredOn}
                    </span>
                    {/* Overdue is derived at read time, never stored -- V1 is
                        explicit that a stored flag would be a copy of today's
                        date frozen into a row. */}
                    {v.overdue && (
                      <span className="ml-1.5 rounded-pill bg-rose-50 px-1.5 py-0.5 text-[10px] font-semibold text-pw-danger">
                        overdue
                      </span>
                    )}
                    {/* The vet's certificate, when the shelter attached one.
                        It comes back as a download rather than rendering in
                        the tab, because a PDF can carry script and the files
                        route is open to anyone -- so this is a link, never an
                        embed. */}
                    {v.certificatePath && (
                      <>
                        {" "}
                        <a href={`${API_BASE}/files/${v.certificatePath}`}
                          target="_blank" rel="noreferrer"
                          className="font-medium text-pw-button hover:underline">
                          certificate
                        </a>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-4 border-t border-pw-stroke/40 pt-3 text-xs text-pw-muted">
                {health
                  ? `${p.shelterName} has not recorded any vaccinations yet.`
                  : `Ask ${p.shelterName} about vaccination records.`}
              </p>
            )}
          </Card>

          <Card className="p-6">
            <h3 className="text-[11px] font-semibold uppercase tracking-wider text-pw-muted">
              Shelter Information
            </h3>
            <p className="mt-2 text-lg font-semibold text-pw-button">{p.shelterName}</p>
            <p className="mt-1 flex items-center gap-1.5 text-sm text-pw-muted">
              <FiMapPin className="h-3.5 w-3.5" /> {p.shelterCity}, {p.shelterState}
            </p>
            <Link to="/app/pets" className="mt-4 inline-block border-t border-pw-stroke/40 pt-3 text-sm font-medium text-pw-button hover:underline">
              Browse all pets →
            </Link>
          </Card>
        </div>
      </div>

      {others.length > 0 && (
        <section className="mt-12">
          <h2 className="text-2xl font-semibold">You might also like</h2>
          <p className="mt-1.5 text-[15px] text-pw-muted">Other pets available for adoption.</p>
          <div className="mt-6 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {others.map((r) => <MatchCard key={r.id} pet={r} />)}
          </div>
        </section>
      )}
    </AdopterLayout>
  );
}
