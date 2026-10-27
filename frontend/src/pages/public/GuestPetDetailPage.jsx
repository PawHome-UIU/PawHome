import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  FiArrowLeft, FiArrowRight, FiHeart, FiInfo, FiAward, FiTag, FiMessageSquare,
  FiImage,
} from "react-icons/fi";
import PublicLayout from "../../components/layout/PublicLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import StatusBadge from "../../components/pets/StatusBadge";
import api from "../../api/client";
import { petAgeLong } from "../../lib/petFormat";
import { API_BASE } from "../../lib/config";

/**
 * A pet profile as a signed-out visitor sees it.
 *
 * This is NOT the adopter's /app/pets/:id with the buttons greyed out. The
 * design withholds specific things -- personality traits, medical history,
 * shelter location -- and then names exactly what it is withholding, which is
 * the honest version of a signup gate: the visitor can see there is more and
 * what "more" is, instead of hitting a wall.
 *
 * What a guest CAN see is deliberate too: the photo, the name, the breed, the
 * age and whether the pet is still available. Enough to want the account.
 *
 * The gate is cosmetic here and must not stay that way. When the API lands,
 * GET /api/v1/pets/:id must return the trimmed projection for an
 * unauthenticated caller -- withholding fields in the component while the
 * endpoint still ships them is a gate anyone can walk around with devtools.
 */
const JOURNEY = [
  { icon: FiAward, title: "Shelter Verified",
    body: "Access detailed background checks and health records for every pet in our sanctuary network." },
  { icon: FiTag, title: "Smart Matching",
    body: "Our empathy-driven algorithm matches you with pets that fit your lifestyle and activity level perfectly." },
  { icon: FiMessageSquare, title: "Direct Support",
    body: "Chat directly with fosters and shelters to ask questions and schedule virtual or in-person meetups." },
];

export default function GuestPetDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const [pet, setPet] = useState(null);
  const [shot, setShot] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/pets/${id}`);
        if (!cancelled) setPet(data);
      } catch {
        // Any failure is the same story to a visitor: this pet is not here.
        // A 404 and a delisted pet are indistinguishable on purpose.
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  if (loading) {
    return (
      <PublicLayout>
        <section className="mx-auto max-w-2xl px-5 py-24 text-center sm:px-8">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </section>
      </PublicLayout>
    );
  }

  // A bad id is a real state, not an impossible one -- a stale link or a pet
  // that has since been adopted and delisted both land here.
  if (!pet) {
    return (
      <PublicLayout>
        <section className="mx-auto max-w-2xl px-5 py-24 text-center sm:px-8">
          <h1 className="text-3xl font-semibold tracking-tight">We couldn't find that pet.</h1>
          <p className="mt-3 text-[15px] text-pw-body">
            They may have already found their forever home. Have a look at who is
            still waiting.
          </p>
          <Button to="/pets" size="lg" className="mt-8">Browse all pets</Button>
        </section>
      </PublicLayout>
    );
  }

  // Cover first, then the rest. photoPaths already comes back cover-first from
  // the server, so this is the fallback for a pet whose only picture is the
  // cover and nothing else.
  const gallery = pet.photoPaths?.length
    ? pet.photoPaths
    : (pet.coverPhotoPath ? [pet.coverPhotoPath] : []);

  return (
    <PublicLayout>
      <section className="mx-auto max-w-6xl px-5 pt-8 sm:px-8">
        <nav aria-label="Breadcrumb" className="flex items-center gap-2 text-sm">
          <button
            onClick={() => navigate(-1)}
            className="inline-flex items-center gap-1.5 text-pw-body hover:text-pw-button"
          >
            <FiArrowLeft className="h-4 w-4" /> Back to results
          </button>
          <span className="text-pw-muted">/</span>
          <span aria-current="page" className="font-medium text-pw-button">{pet.name}</span>
        </nav>

        <div className="mt-6 grid gap-8 lg:grid-cols-2">
          <div>
            <div className="relative overflow-hidden rounded-card bg-pw-sidebar/25">
              <div className="relative aspect-[4/3] overflow-hidden shrink-0">
                {gallery.length > 0 ? (
                  <img src={`${API_BASE}/files/${gallery[shot] ?? gallery[0]}`}
                    alt={pet.name} className="absolute inset-0 h-full w-full object-cover" />
                ) : (
                  <span className="flex h-full w-full items-center justify-center">
                    <FiImage className="h-9 w-9 text-pw-stroke" />
                  </span>
                )}
              </div>
              <span className="absolute left-4 top-4">
                <StatusBadge status={pet.status} />
              </span>
            </div>

            {/* Thumbnails only when there is more than one, because a strip of
                one is a control that does nothing. */}
            {gallery.length > 1 && (
              <div className="mt-3 flex flex-wrap gap-3">
                {gallery.map((path, i) => (
                  <button key={path} type="button" onClick={() => setShot(i)}
                    aria-label={`Photo ${i + 1} of ${pet.name}`}
                    aria-pressed={i === shot}
                    className={`h-20 w-20 overflow-hidden rounded-2xl border-2 transition-colors ${
                      i === shot ? "border-pw-button" : "border-transparent hover:border-pw-stroke"}`}>
                    <img src={`${API_BASE}/files/${path}`} alt="" loading="lazy"
                      className="h-full w-full object-cover" />
                  </button>
                ))}
              </div>
            )}
          </div>

          <Card className="flex h-fit flex-col p-8 sm:p-10">
            <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">{pet.name}</h1>
            <p className="mt-2 text-lg text-pw-body">
              {pet.breed} · {petAgeLong(pet.ageValue, pet.ageUnit)}
            </p>

            <div className="mt-7 flex gap-3 rounded-card bg-pw-input p-5">
              <FiInfo className="mt-0.5 h-5 w-5 shrink-0 text-pw-button" />
              <div>
                <p className="text-[15px] font-semibold text-pw-ink">
                  Want to know more about {pet.name}?
                </p>
                {/* Naming the withheld fields is the point. A gate that just
                    says "sign up for more" tells the visitor nothing about
                    whether more is worth an account. */}
                <p className="mt-1.5 text-[15px] leading-relaxed text-pw-body">
                  Sign up to see full details, save pets, and apply for adoption.
                  You'll get access to {pet.sex === "FEMALE" ? "her" : "his"}{" "}
                  personality traits, medical history, and shelter location.
                </p>
              </div>
            </div>

            <Button to="/join" size="lg" full className="mt-7">
              Sign up to Apply <FiArrowRight className="h-4 w-4" />
            </Button>

            {/* Saving needs an account too, so this goes to the same place
                rather than pretending to save and losing it on reload. */}
            <Button to="/join" variant="outline" size="lg" full className="mt-3">
              <FiHeart className="h-4 w-4" /> Save to Favorites
            </Button>

            <p className="mt-5 text-center text-xs text-pw-muted">
              Joining PawHome is free and only takes 30 seconds.
            </p>
          </Card>
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-5 py-20 sm:px-8">
        <h2 className="text-center text-3xl font-semibold tracking-tight sm:text-4xl">
          Experience the full PawHome journey
        </h2>

        <div className="mt-10 grid gap-6 md:grid-cols-3">
          {JOURNEY.map(({ icon: Icon, title, body }) => (
            <div key={title} className="rounded-card border border-pw-stroke/50 bg-pw-tint px-7 py-9 text-center">
              <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-pw-input text-pw-button">
                <Icon className="h-5 w-5" />
              </span>
              <h3 className="mt-5 text-xl font-semibold">{title}</h3>
              <p className="mt-3 text-[15px] leading-relaxed text-pw-body">{body}</p>
            </div>
          ))}
        </div>
      </section>
    </PublicLayout>
  );
}
