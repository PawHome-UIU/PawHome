import { FiHeart, FiShield, FiUsers, FiMapPin, FiMail } from "react-icons/fi";
import PublicLayout from "../../components/layout/PublicLayout";
import SectionHeading from "../../components/sections/SectionHeading";
import FeatureRow from "../../components/sections/FeatureRow";
import CtaBand from "../../components/sections/CtaBand";
import Card from "../../components/ui/Card";

// NOTE: there is no Figma frame for this page. It is built from the design
// system and from the content the footer already promises (About, Contact,
// Careers), so it is consistent with the rest of the site rather than invented
// from nothing. Swap the copy freely -- no component here is bespoke.

const VALUES = [
  { icon: FiHeart, title: "The animal comes first",
    body: "Every decision in this system, from how a match is scored to how a rejection is worded, is judged by whether it leads to a pet staying home." },
  { icon: FiShield, title: "A record that follows the pet",
    body: "Vaccination history, daily care and behaviour notes stay attached to the animal — through adoption, through boarding, for life." },
  { icon: FiUsers, title: "Shelters are partners, not listings",
    body: "Staff get real tools: an application queue, care schedules and reporting, rather than a form that emails them a stranger's details." },
];

const STATS = [
  { value: "15,000+", label: "Pets adopted" },
  { value: "250+", label: "Partner shelters" },
  { value: "12,000+", label: "Happy families" },
  { value: "94%", label: "Stay in their home" },
];

export default function AboutPage() {
  return (
    <PublicLayout>
      <section className="bg-pw-box">
        <div className="mx-auto max-w-4xl px-5 py-20 text-center sm:px-8">
          <span className="inline-block rounded-pill bg-white px-4 py-1.5 text-xs font-medium text-pw-button">
            About PawHome
          </span>
          <h1 className="mt-6 text-4xl font-semibold leading-tight tracking-tight sm:text-5xl">
            Adoption shouldn't end at the door
          </h1>
          <span className="mx-auto mt-5 block h-1 w-12 rounded-full bg-pw-button" />
          <p className="mx-auto mt-6 max-w-2xl text-[15px] leading-relaxed text-pw-body">
            Most platforms stop the moment a pet is collected. PawHome keeps the
            animal, its shelter and its history connected afterwards — which is
            what makes boarding with the people who raised them possible.
          </p>
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-5 py-16 sm:px-8">
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {STATS.map((s) => (
            <Card key={s.label} className="p-6 text-center">
              <p className="text-3xl font-semibold text-pw-button">{s.value}</p>
              <p className="mt-1.5 text-sm text-pw-muted">{s.label}</p>
            </Card>
          ))}
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-5 pb-16 sm:px-8">
        <SectionHeading
          title="What we believe"
          sub="Three commitments that decide how this platform is built."
        />
        <div className="mt-12 grid gap-6 md:grid-cols-3">
          {VALUES.map(({ icon: Icon, title, body }) => (
            <Card key={title} className="p-7">
              <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-pw-input text-pw-button">
                <Icon className="h-5 w-5" />
              </span>
              <h3 className="mt-5 text-lg font-semibold leading-tight">{title}</h3>
              <p className="mt-3 text-sm leading-relaxed text-pw-muted">{body}</p>
            </Card>
          ))}
        </div>
      </section>

      <section className="bg-pw-box/70">
        <div className="mx-auto max-w-6xl px-5 py-20 sm:px-8">
          <FeatureRow
            eyebrow="Our story"
            title="Built with shelters, not just for them"
            body="PawHome began as a university project with a simple observation: shelters were tracking vaccinations on paper, and adopters were choosing pets from a photograph. Neither had the information they needed."
            points={[
              "Every screen was designed before a single table was created.",
              "The matching engine explains itself — no unexplainable score.",
              "Boarding exists because a pet's record survives its adoption.",
            ]}
          />
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-5 py-20 sm:px-8">
        <SectionHeading title="Get in touch" sub="We'd like to hear from shelters, adopters and anyone curious about the project." />
        <div className="mx-auto mt-10 grid max-w-2xl gap-6 sm:grid-cols-2">
          <Card className="flex items-center gap-4 p-6">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-pw-input text-pw-button">
              <FiMail className="h-5 w-5" />
            </span>
            <div>
              <p className="text-sm font-semibold text-pw-ink">Email us</p>
              <p className="mt-0.5 text-sm text-pw-muted">hello@pawhome.local</p>
            </div>
          </Card>
          <Card className="flex items-center gap-4 p-6">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-pw-input text-pw-button">
              <FiMapPin className="h-5 w-5" />
            </span>
            <div>
              <p className="text-sm font-semibold text-pw-ink">Find a shelter</p>
              <p className="mt-0.5 text-sm text-pw-muted">250 partners nationwide</p>
            </div>
          </Card>
        </div>
      </section>

      <CtaBand
        layout="split"
        title="Ready to meet your new best friend?"
        body="Thousands of pets are waiting for a home right now."
        action="Browse Pets"
        to="/pets"
      />
    </PublicLayout>
  );
}
