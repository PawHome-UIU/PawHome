import {
  FiHome, FiCoffee, FiActivity, FiCamera, FiHeart, FiArrowRight,
  FiFileText, FiTarget, FiHelpCircle,
} from "react-icons/fi";
import PublicLayout from "../../components/layout/PublicLayout";
import SectionHeading from "../../components/sections/SectionHeading";
import FeatureRow from "../../components/sections/FeatureRow";
import CtaBand from "../../components/sections/CtaBand";
import Accordion from "../../components/ui/Accordion";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";

const CARE = [
  { icon: FiHome, title: "Safe boarding", body: "24/7 monitored facilities with climate-controlled suites." },
  { icon: FiCoffee, title: "Daily feeding", body: "Strict adherence to dietary plans and medication schedules." },
  { icon: FiActivity, title: "Exercise", body: "Three daily play sessions in supervised private yards." },
  { icon: FiCamera, title: "Health updates", body: "Daily high-quality photos and health reports via the app." },
  { icon: FiHeart, title: "Special care", body: "Customised routines for seniors or pets with anxiety." },
];

const STEPS = [
  { n: 1, title: "Submit request" },
  { n: 2, title: "Choose dates" },
  { n: 3, title: "Add care info" },
  { n: 4, title: "Drop off & updates" },
];

const MORE = [
  { icon: FiFileText, title: "Adoption Assistance", body: "Complete support through the adoption paperwork and legal process.", cta: "Learn more", to: "/features" },
  { icon: FiTarget, title: "Personality Matching", body: "Advanced behavioural assessments to find your perfect lifelong companion.", cta: "Learn more", to: "/features" },
  { icon: FiHelpCircle, title: "Eligibility Quiz", body: "Find out which pets fit your lifestyle and home environment best.", cta: "Start Quiz", to: "/join" },
];

const FAQ = [
  { q: "How much does boarding cost?",
    a: "Standard boarding starts at ৳35 per night, with premium suites available at ৳55 per night. Discounts apply for stays longer than one week." },
  { q: "What do I need to bring for my pet?",
    a: "We recommend bringing their current food to avoid tummy upsets, a favourite toy, and any necessary medication in its original packaging." },
  { q: "How long can my pet stay?",
    a: "Boarding can be booked for as little as one night, or up to three weeks. For longer stays, please contact the shelter directly." },
  { q: "What happens in an emergency?",
    a: "All partner shelters have 24/7 on-call veterinary access. We contact you immediately, and act on the emergency vet authorisation you gave when booking." },
];

export default function ServicesPage() {
  return (
    <PublicLayout>
      {/* ------------------------------------------------------- hero ---- */}
      <section className="mx-auto grid max-w-6xl items-center gap-12 px-5 py-16 sm:px-8 lg:grid-cols-2">
        <div>
          <h1 className="text-4xl font-semibold leading-tight tracking-tight sm:text-5xl">
            Going away? We'll care for your pet.
          </h1>
          <p className="mt-5 max-w-md text-[15px] leading-relaxed text-pw-body">
            Experience peace of mind with our secure boarding services at
            certified partner shelters. Your pet receives professional care in a
            familiar, loving environment while you travel.
          </p>
          <Button to="/join" size="lg" className="mt-8">Request Boarding</Button>
        </div>
        <div className="aspect-[4/3] rounded-card bg-pw-box" />
      </section>

      {/* ---------------------------------------------------- included --- */}
      <section className="bg-pw-box/70">
        <div className="mx-auto max-w-6xl px-5 py-16 sm:px-8">
          <SectionHeading title="Everything your pet needs to thrive" />
          <div className="mt-10 grid gap-5 sm:grid-cols-2 lg:grid-cols-5">
            {CARE.map(({ icon: Icon, title, body }) => (
              <Card key={title} className="p-5 text-center">
                <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-full bg-pw-input text-pw-button">
                  <Icon className="h-5 w-5" />
                </span>
                <h3 className="mt-4 text-sm font-semibold">{title}</h3>
                <p className="mt-2 text-xs leading-relaxed text-pw-muted">{body}</p>
              </Card>
            ))}
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------ steps ---- */}
      <section className="mx-auto max-w-5xl px-5 py-16 sm:px-8">
        <SectionHeading title="How Boarding Works" />
        <ol className="relative mt-12 grid gap-8 sm:grid-cols-4">
          {/* The connecting rail sits behind the numbers and is hidden on
              small screens, where the steps stack and a horizontal line would
              point nowhere. */}
          <span className="absolute left-[12%] right-[12%] top-5 hidden h-px bg-pw-stroke/60 sm:block" aria-hidden="true" />
          {STEPS.map(({ n, title }) => (
            <li key={n} className="relative text-center">
              <span className="mx-auto flex h-10 w-10 items-center justify-center rounded-full bg-pw-button text-sm font-semibold text-white ring-8 ring-pw-tint">
                {n}
              </span>
              <p className="mt-4 text-xs font-semibold uppercase tracking-wider text-pw-button">Step {n}</p>
              <p className="mt-1 text-[15px] font-medium text-pw-ink">{title}</p>
            </li>
          ))}
        </ol>
      </section>

      {/* ------------------------------------------------ booking form --- */}
      <section className="bg-pw-box">
        <div className="mx-auto grid max-w-6xl items-center gap-12 px-5 py-16 sm:px-8 lg:grid-cols-2">
          <div>
            <h2 className="text-3xl font-semibold leading-tight">Ready to book a stay?</h2>
            <p className="mt-4 max-w-md text-[15px] leading-relaxed text-pw-body">
              Fill out this quick form to check availability at your nearest
              partner shelter. We'll match your pet with the best available
              caregiver based on their personality and needs.
            </p>
          </div>

          {/* Deliberately inert on the public page: boarding is only for pets
              you have already adopted, so the form previews the flow and the
              button sends you to sign up rather than failing after typing. */}
          <Card className="p-7 shadow-card">
            <div className="space-y-4">
              <div>
                <label className="mb-1.5 block text-sm font-medium text-pw-ink">Select Pet</label>
                <select disabled className="h-12 w-full rounded-2xl border border-pw-stroke/60 bg-pw-input px-4 text-[15px] text-pw-muted">
                  <option>Log in to see your adopted pets</option>
                </select>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1.5 block text-sm font-medium text-pw-ink">Start Date</label>
                  <input type="date" disabled className="h-12 w-full rounded-2xl border border-pw-stroke/60 bg-pw-input px-4 text-[15px] text-pw-muted" />
                </div>
                <div>
                  <label className="mb-1.5 block text-sm font-medium text-pw-ink">End Date</label>
                  <input type="date" disabled className="h-12 w-full rounded-2xl border border-pw-stroke/60 bg-pw-input px-4 text-[15px] text-pw-muted" />
                </div>
              </div>
              <div>
                <label className="mb-1.5 block text-sm font-medium text-pw-ink">Special Care Instructions</label>
                <textarea rows={3} disabled
                  placeholder="Any medication, allergies or habits we should know about?"
                  className="w-full rounded-2xl border border-pw-stroke/60 bg-pw-input px-4 py-3 text-[15px] text-pw-muted placeholder:text-pw-muted" />
              </div>
              <Button to="/join" full size="lg" className="!mt-6">
                Sign up to request boarding
              </Button>
            </div>
          </Card>
        </div>
      </section>

      {/* ---------------------------------------------------- updates ---- */}
      <section className="mx-auto max-w-6xl px-5 py-20 sm:px-8">
        <FeatureRow
          title="Stay connected, even from miles away"
          body="We know it's hard to be away. That's why the system prioritises transparency: you receive a daily update while your pet is with us."
          points={[
            "A live photo gallery of play sessions.",
            "Verified feeding and medication timestamps.",
            "A running timeline posted by your pet's caregiver.",
          ]}
        />
      </section>

      {/* --------------------------------------------------- more help --- */}
      <section className="bg-pw-box/70">
        <div className="mx-auto max-w-6xl px-5 py-16 sm:px-8">
          <SectionHeading title="More ways we help" />
          <div className="mt-10 grid gap-6 md:grid-cols-3">
            {MORE.map(({ icon: Icon, ...m }) => (
              <Card key={m.title} className="flex h-full flex-col p-6">
                <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-pw-input text-pw-button">
                  <Icon className="h-5 w-5" />
                </span>
                <h3 className="mt-4 text-base font-semibold">{m.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-pw-muted">{m.body}</p>
                <Button to={m.to} variant="ghost" size="sm" className="mt-auto justify-start pt-4 !px-0">
                  {m.cta} <FiArrowRight className="h-4 w-4" />
                </Button>
              </Card>
            ))}
          </div>
        </div>
      </section>

      {/* --------------------------------------------------------- faq --- */}
      <section className="mx-auto max-w-3xl px-5 py-20 sm:px-8">
        <SectionHeading title="Frequently Asked Questions" className="mb-10" />
        <Accordion items={FAQ} />
      </section>

      <CtaBand
        title="Travel worry-free — your pet is in good hands."
        action="Request Boarding Now"
        to="/join"
      />
    </PublicLayout>
  );
}
