import {
  FiSearch, FiTarget, FiHelpCircle, FiFileText, FiBell, FiShield,
  FiClipboard, FiCamera, FiBarChart2, FiArrowRight, FiZap,
} from "react-icons/fi";
import PublicLayout from "../../components/layout/PublicLayout";
import SectionHeading from "../../components/sections/SectionHeading";
import FeatureRow from "../../components/sections/FeatureRow";
import CtaBand from "../../components/sections/CtaBand";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";

// The nine cards map one-to-one onto the nine feature areas in the schema.
// That is deliberate: nothing is advertised here that the database cannot back.
const FEATURES = [
  { icon: FiSearch, title: "Browse Pets",
    body: "Advanced filtering and high-definition galleries to find your new best friend." },
  { icon: FiTarget, title: "Personality Matching",
    body: "Our matching algorithm aligns your lifestyle with a pet's unique temperament." },
  { icon: FiHelpCircle, title: "Eligibility Quiz",
    body: "Quickly check your readiness for adoption with our intuitive self-assessment tool." },
  { icon: FiFileText, title: "Adoption Applications",
    body: "Digital, easy-to-track applications that save your progress across multiple shelters." },
  { icon: FiBell, title: "Pet Recommendations",
    body: "Get personalised alerts when a pet matching your profile arrives at a local shelter." },
  { icon: FiShield, title: "Vaccination Records",
    body: "Secure, cloud-hosted digital medical history for every animal in the network." },
  { icon: FiClipboard, title: "Daily Care Logs",
    body: "Keep track of feeding, exercise and behaviour notes during the adjustment period." },
  { icon: FiCamera, title: "Health Updates & Photos",
    body: "Real-time updates from shelters including vet visits and candid photos of pets." },
  { icon: FiBarChart2, title: "Adoption Reports",
    body: "Comprehensive data insights for shelters to track successful rehoming metrics." },
];

export default function FeaturesPage() {
  return (
    <PublicLayout>
      {/* ------------------------------------------------------- hero ---- */}
      <section className="bg-pw-box">
        <div className="mx-auto max-w-4xl px-5 py-20 text-center sm:px-8">
          <span className="inline-block rounded-pill bg-white px-4 py-1.5 text-xs font-medium text-pw-button">
            Platform Overview
          </span>
          <h1 className="mt-6 text-4xl font-semibold leading-tight tracking-tight sm:text-5xl">
            Everything you need to adopt with confidence
          </h1>
          <span className="mx-auto mt-5 block h-1 w-12 rounded-full bg-pw-button" />
          <p className="mx-auto mt-6 max-w-2xl text-[15px] leading-relaxed text-pw-body">
            From finding your perfect match to managing the first days of
            homecoming, PawHome provides a seamless sanctuary for pets and their
            future families.
          </p>
        </div>
      </section>

      {/* --------------------------------------------------- the nine ---- */}
      <section className="mx-auto max-w-6xl px-5 py-20 sm:px-8">
        <SectionHeading
          title="Core Ecosystem Features"
          sub="Tools designed to make the transition into pet parenthood joyful and stress-free."
        />
        <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map(({ icon: Icon, title, body }) => (
            <Card key={title} className="p-6">
              <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-pw-input text-pw-button">
                <Icon className="h-5 w-5" />
              </span>
              <h3 className="mt-4 text-base font-semibold">{title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-pw-muted">{body}</p>
            </Card>
          ))}
        </div>
      </section>

      {/* ------------------------------------------- one row per role ---- */}
      <section className="bg-pw-box/70">
        <div className="mx-auto max-w-6xl space-y-20 px-5 py-20 sm:px-8">
          <FeatureRow
            eyebrow="For adopters"
            title="Find Your Perfect Life Companion"
            points={[
              "Personalised pet matches based on your home environment and energy level.",
              "A single dashboard for every pending application and message.",
              "A digital pet passport with medical history ready for your local vet.",
            ]}
          />
          <FeatureRow
            flip
            eyebrow="For shelter staff"
            title="Streamline Shelter Operations"
            points={[
              "Automated applicant screening, so you focus on the most qualified families first.",
              "Pets, vaccinations and daily care records managed in one platform.",
              "Structured decisions that tell an applicant why, not just no.",
            ]}
          />
          <FeatureRow
            eyebrow="For network admins"
            title="Data-Driven Animal Welfare"
            points={[
              "Regional oversight of adoption success rates and shelter resource allocation.",
              "Reporting tools that export the numbers an audit actually asks for.",
              "One question bank powering eligibility, personality and boarding quizzes.",
            ]}
          />
        </div>
      </section>

      {/* --------------------------------------------------- matching ---- */}
      <section className="mx-auto max-w-6xl px-5 py-20 sm:px-8">
        <div className="grid items-center gap-12 lg:grid-cols-2">
          <div>
            <span className="inline-flex items-center gap-1.5 rounded-pill bg-pw-input px-3 py-1.5 text-xs font-medium text-pw-button">
              <FiZap className="h-3.5 w-3.5" /> Personality Matching
            </span>
            <h2 className="mt-5 text-3xl font-semibold leading-tight sm:text-4xl">
              The Science of a Tail-Wagging Match
            </h2>
            <p className="mt-5 text-[15px] leading-relaxed text-pw-body">
              Adoption shouldn't be a guessing game. Our quiz maps your answers
              onto the same seven temperament traits a shelter records for every
              animal — so a match is a comparison you can read, not a number you
              have to trust.
            </p>
            <div className="mt-8 flex flex-wrap items-center gap-5">
              <Button to="/join">Take the Quiz</Button>
              <a href="#top" className="inline-flex items-center gap-1.5 text-sm font-medium text-pw-button hover:underline">
                Learn how it works <FiArrowRight className="h-4 w-4" />
              </a>
            </div>
          </div>
          <div className="aspect-[4/3] rounded-card border border-pw-stroke/50 bg-pw-box" />
        </div>
      </section>

      <CtaBand
        layout="split"
        title="Ready to find your match?"
        action="Browse Pets"
        to="/pets"
      />
    </PublicLayout>
  );
}
