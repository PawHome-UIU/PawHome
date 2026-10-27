import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  FiSearch, FiHelpCircle, FiHeart, FiCheckSquare, FiCheckCircle, FiArrowRight,
} from "react-icons/fi";
import PublicLayout from "../../components/layout/PublicLayout";
import Button from "../../components/ui/Button";
import Card from "../../components/ui/Card";
import PetCard from "../../components/pets/PetCard";
import CtaBand from "../../components/sections/CtaBand";
import api from "../../api/client";

const STATS = [
  { value: "15,000+", label: "Pets Adopted" },
  { value: "250+", label: "Partner Shelters" },
  { value: "12,000+", label: "Happy Families" },
];

const STEPS = [
  { icon: FiSearch, title: "Browse pets",
    body: "Explore thousands of adorable dogs, cats and small animals waiting for a home." },
  { icon: FiHelpCircle, title: "Eligibility quiz",
    body: "Take a short 2-minute quiz to help us understand your living situation and lifestyle." },
  { icon: FiHeart, title: "Get matched",
    body: "Our algorithm recommends pets that align with your personality and home energy." },
  { icon: FiCheckSquare, title: "Apply to adopt",
    body: "Connect directly with shelters and start the application process with one click." },
];

export default function LandingPage() {
  // The three pets the landing page teases come from the same endpoint the
  // browse grid reads, so they cannot disagree.
  //
  // A FAILURE HERE IS SILENT AND THE SECTION SIMPLY EMPTIES. This is the front
  // door: an error banner about a fetch, on a page whose job is to explain
  // what PawHome is, would be the worst possible first impression of it.
  const [featured, setFeatured] = useState([]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/pets");
        if (!cancelled) setFeatured(data.slice(0, 3));
      } catch {
        // Deliberately quiet -- see above.
      }
    })();
    return () => { cancelled = true; };
  }, []);

  return (
    <PublicLayout>
        {/* ---------------------------------------------------- hero ---- */}
        <section className="mx-auto grid max-w-6xl items-center gap-12 px-5 py-16 sm:px-8 lg:grid-cols-2 lg:py-24">
          <div>
            <h1 className="text-4xl font-semibold leading-[1.1] tracking-tight sm:text-5xl">
              Find your perfect companion
            </h1>
            <p className="mt-5 max-w-md text-[15px] leading-relaxed text-pw-body">
              Shelter pets are more than just animals — they're family. Our
              personality-matching technology connects you with a furry friend
              who truly fits your lifestyle. Plus, enjoy exclusive pet boarding
              for your newly adopted pet.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Button to="/pets">Browse Pets</Button>
              <Button to="/join" variant="outline">Take the Match Quiz</Button>
            </div>
          </div>

          {/* Collage frame. Real photography drops straight in; until then the
              tinted blocks keep the composition honest rather than shipping a
              broken <img>. */}
          <div className="rounded-card border border-pw-stroke bg-pw-box p-3 shadow-card">
            <div className="grid grid-cols-2 gap-3">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="aspect-[4/3] rounded-2xl bg-pw-sidebar/25" />
              ))}
            </div>
          </div>
        </section>

        {/* --------------------------------------------------- stats ---- */}
        <section className="bg-pw-box">
          <div className="mx-auto grid max-w-6xl gap-8 px-5 py-10 sm:px-8 sm:grid-cols-3">
            {STATS.map((s) => (
              <div key={s.label} className="text-center">
                <p className="text-3xl font-semibold text-pw-button">{s.value}</p>
                <p className="mt-1 text-xs font-medium tracking-wide text-pw-body">{s.label}</p>
              </div>
            ))}
          </div>
        </section>

        {/* ---------------------------------------------- how it works -- */}
        <section id="features" className="mx-auto max-w-6xl scroll-mt-20 px-5 py-20 sm:px-8">
          <h2 className="text-center text-3xl font-semibold">How It Works</h2>
          <p className="mt-2 text-center text-[15px] text-pw-muted">
            Your journey to a new best friend starts here.
          </p>

          <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {STEPS.map(({ icon: Icon, title, body }) => (
              <Card key={title} className="p-6 text-center">
                <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-full bg-pw-input text-pw-button">
                  <Icon className="h-5 w-5" />
                </span>
                <h3 className="mt-4 text-base font-semibold">{title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-pw-muted">{body}</p>
              </Card>
            ))}
          </div>
        </section>

        {/* ------------------------------------------- featured pets ---- */}
        <section className="bg-pw-box/60">
          <div className="mx-auto max-w-6xl px-5 py-20 sm:px-8">
            <div className="flex flex-wrap items-end justify-between gap-4">
              <div>
                <h2 className="text-3xl font-semibold">Meet your new friends</h2>
                <p className="mt-1.5 text-[15px] text-pw-muted">Pets available for adoption near you.</p>
              </div>
              <Link to="/pets"
                className="inline-flex items-center gap-1.5 text-sm font-medium text-pw-button hover:underline">
                View All Pets <FiArrowRight className="h-4 w-4" />
              </Link>
            </div>

            <div className="mt-10 grid gap-6 md:grid-cols-3">
              {featured.map((pet) => (
                <PetCard key={pet.id} pet={pet} />
              ))}
            </div>
          </div>
        </section>

        {/* ------------------------------------------------- matching ---- */}
        <section id="services" className="mx-auto max-w-6xl scroll-mt-20 px-5 py-20 sm:px-8">
          <div className="grid items-center gap-12 lg:grid-cols-2">
            <div className="aspect-[4/3] rounded-card border border-pw-stroke/60 bg-pw-box" />
            <div>
              <h2 className="text-3xl font-semibold leading-tight">
                Matches based on personality, not just looks
              </h2>
              <p className="mt-4 text-[15px] leading-relaxed text-pw-body">
                We know that every pet and owner is unique. Our matching engine
                compares the temperament recorded for each animal against the
                profile built from your quiz answers — so a recommendation can
                always explain itself.
              </p>
              <ul className="mt-6 space-y-3">
                {["Comprehensive behavioural profiling",
                  "Home compatibility assessment",
                  "Continuous support post-adoption"].map((t) => (
                  <li key={t} className="flex items-center gap-2.5 text-[15px] text-pw-body">
                    <FiCheckCircle className="h-5 w-5 shrink-0 text-pw-button" />
                    {t}
                  </li>
                ))}
              </ul>
              <Button to="/join" className="mt-8">Start Your Quiz</Button>
            </div>
          </div>
        </section>

        {/* ------------------------------------------------- boarding ---- */}
        <section id="about" className="mx-auto max-w-6xl scroll-mt-20 px-5 pb-20 sm:px-8">
          <Card tinted className="grid items-center gap-8 overflow-hidden p-8 lg:grid-cols-2 lg:p-10">
            <div>
              <h2 className="text-3xl font-semibold leading-tight">
                Premium Boarding for Your Adopted Pet
              </h2>
              <p className="mt-4 text-[15px] leading-relaxed text-pw-body">
                Going on vacation? Bring your pet back to a place they know and
                trust. Our boarding service is exclusively available for PawHome
                alumni, featuring cage-free suites and 24/7 care.
              </p>
              <Button to="/join" variant="outline" size="sm" className="mt-6">Learn more</Button>
            </div>
            <div className="aspect-[16/10] rounded-2xl bg-pw-sidebar/25" />
          </Card>
        </section>

        <CtaBand
          title="Ready to meet your new best friend?"
          body="Thousands of wonderful pets are waiting for a home right now. Start your journey today and find the companion you've been waiting for."
          action="Get Started"
          to="/join"
        />
    </PublicLayout>
  );
}
