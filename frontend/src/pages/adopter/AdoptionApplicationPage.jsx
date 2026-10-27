import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { FiCheckCircle, FiShield, FiArrowLeft, FiInfo } from "react-icons/fi";
import AdopterLayout from "../../components/adopter/AdopterLayout";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Input from "../../components/ui/Input";
import StatusBadge from "../../components/pets/StatusBadge";
import EligibilityQuizModal from "../../components/adopter/EligibilityQuizModal";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { useAuth } from "../../context/AuthContext";
import { API_BASE } from "../../lib/config";

const STEPS = ["Your details", "Home & lifestyle", "Review"];

export default function AdoptionApplicationPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();

  const [pet, setPet] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [step, setStep] = useState(0);
  const [quizOpen, setQuizOpen] = useState(false);
  /**
   * The ATTEMPT, not a boolean.
   *
   * An application is filed against eligibility_attempt_id -- the composite
   * foreign key to (quiz_attempts.id, quiz_type) is what makes it impossible
   * to attach a personality attempt instead. "The quiz is done" is not enough
   * information to submit anything.
   */
  const [attempt, setAttempt] = useState(null);
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState("");
  /**
   * Prefilled from the signed-in account by a useState INITIALISER, not an
   * effect. ProtectedRoute holds this route until AuthContext has finished
   * checking, so `user` is already there on the first render -- and an effect
   * that seeds a form is a setState in an effect body, which cascades a render
   * and fights anything typed before it lands.
   *
   * Seeded, not mirrored: the application keeps its own copy of these details,
   * so an applicant is free to give a different address from their profile.
   */
  const [form, setForm] = useState(() => ({
    fullName: user?.fullName ?? "", email: user?.email ?? "", phone: "",
    city: "", address: "", homeType: "", timeAtHome: "", experience: "", household: "",
  }));
  const [errors, setErrors] = useState({});

  // The pet, and any eligibility attempt already sat. Somebody who took the
  // quiz last week should not be made to take it again to apply for a second
  // pet -- quiz_attempts has no expiry and inventing one here would be a rule
  // nobody wrote down.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [petRes, attemptRes] = await Promise.all([
          api.get(`/pets/${id}`),
          api.get("/quiz/ELIGIBILITY/attempts/latest"),
        ]);
        if (cancelled) return;
        setPet(petRes.data);
        // 204 when they have never sat it, which axios gives as empty data.
        if (attemptRes.status === 200 && attemptRes.data) setAttempt(attemptRes.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load that pet."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  const quizDone = attempt != null;

  const set = (k) => (e) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    if (errors[k]) setErrors((x) => ({ ...x, [k]: undefined }));
  };

  function validate() {
    const n = {};
    if (step === 0) {
      if (!form.fullName.trim()) n.fullName = "Full name is required";
      if (!form.email.trim()) n.email = "Email address is required";
      if (!form.phone.trim()) n.phone = "Phone number is required";
      if (!form.address.trim()) n.address = "Residential address is required";
      if (!form.city.trim()) n.city = "City is required";
    }
    if (step === 1) {
      if (!form.homeType.trim()) n.homeType = "Home type is required";
      if (!form.timeAtHome.trim()) n.timeAtHome = "This helps the shelter judge fit";
      if (!form.experience.trim()) n.experience = "Tell the shelter about your experience";
    }
    setErrors(n);
    return Object.keys(n).length === 0;
  }

  /**
   * Send it.
   *
   * The score is NOT in this payload and must never be: the server reads it
   * off the attempt. An applicant who could post their own quiz_score would be
   * marking their own eligibility, and it is the first thing a reviewer sees.
   */
  async function submit() {
    setBusy(true);
    setSubmitError("");
    try {
      await api.post("/applications", {
        petId: pet.id,
        eligibilityAttemptId: attempt.id,
        applicantName: form.fullName.trim(),
        applicantEmail: form.email.trim(),
        applicantPhone: form.phone.trim(),
        applicantAddress: form.address.trim(),
        applicantCity: form.city.trim(),
        homeType: form.homeType.trim(),
        timeAtHome: form.timeAtHome.trim(),
        petExperience: form.experience.trim(),
        householdNote: form.household.trim() || null,
      });
      setSubmitted(true);
    } catch (err) {
      // Stays on the review step holding everything typed. The likely
      // refusals -- the pet went while this was open, an application already
      // pending -- are ones the person reads and acts on, not retypes.
      setSubmitError(apiError(err, "That application could not be submitted."));
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <AdopterLayout>
        <Card className="mx-auto max-w-xl p-10 text-center">
          <p className="text-[15px] text-pw-muted">Loading…</p>
        </Card>
      </AdopterLayout>
    );
  }

  if (loadError || !pet) {
    return (
      <AdopterLayout>
        <Card className="mx-auto max-w-xl p-10 text-center">
          <p className="text-[15px] font-medium text-pw-danger">
            {loadError || "That pet could not be found."}
          </p>
          <Button to="/app/pets" className="mt-6">Browse pets</Button>
        </Card>
      </AdopterLayout>
    );
  }

  if (submitted) {
    return (
      <AdopterLayout>
        <Card className="mx-auto max-w-xl p-10 text-center">
          <span className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-green-50 text-green-600">
            <FiCheckCircle className="h-8 w-8" />
          </span>
          <h1 className="mt-6 text-3xl font-semibold">Application submitted</h1>
          <p className="mt-3 text-[15px] leading-relaxed text-pw-body">
            Your application for <strong className="text-pw-ink">{pet.name}</strong> is with{" "}
            {pet.shelterName}. Typical response time is 2–3 business days, and you'll
            see every stage on your applications page.
          </p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <Button to="/app/applications">Track this application</Button>
            <Button to="/app/pets" variant="outline">Keep browsing</Button>
          </div>
        </Card>
      </AdopterLayout>
    );
  }

  return (
    <AdopterLayout>
      <button onClick={() => navigate(-1)}
        className="mb-5 inline-flex items-center gap-2 text-sm font-medium text-pw-button hover:underline">
        <FiArrowLeft className="h-4 w-4" /> Back to {pet.name}
      </button>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="p-7 lg:col-span-2">
          <h1 className="text-3xl font-semibold">Adoption Application</h1>

          <ol className="mt-6 flex gap-2">
            {STEPS.map((s, i) => (
              <li key={s} className="flex-1">
                <div className="flex items-center gap-2.5">
                  <span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${
                    i <= step ? "bg-pw-button text-white" : "bg-pw-input text-pw-muted"}`}>
                    {i + 1}
                  </span>
                  <span className={`text-sm ${i === step ? "font-medium text-pw-ink" : "text-pw-muted"}`}>{s}</span>
                </div>
                <span className={`mt-2 block h-0.5 rounded-full ${i <= step ? "bg-pw-button" : "bg-pw-input"}`} />
              </li>
            ))}
          </ol>

          <div className={`mt-6 flex items-center gap-2.5 rounded-2xl px-5 py-3.5 ${
            quizDone ? "bg-green-50 text-green-800" : "bg-pw-input text-pw-body"}`}>
            {quizDone ? <FiCheckCircle className="h-5 w-5 shrink-0" /> : <FiInfo className="h-5 w-5 shrink-0 text-pw-button" />}
            <span className="flex-1 text-sm">
              {quizDone
                ? "Eligibility quiz completed — attached to this application."
                : "The eligibility quiz must be completed before you can submit."}
            </span>
            {!quizDone && (
              <Button size="sm" onClick={() => setQuizOpen(true)}>Take quiz</Button>
            )}
          </div>

          {step === 0 && (
            <div className="mt-7 grid gap-4 sm:grid-cols-2">
              <Input label="Full Name" value={form.fullName} onChange={set("fullName")} error={errors.fullName} />
              <Input label="Email Address" type="email" value={form.email} onChange={set("email")} error={errors.email} />
              <Input label="Phone Number" value={form.phone} onChange={set("phone")} error={errors.phone} placeholder="+1 (555) 000-0000" />
              <Input label="Current City" value={form.city} onChange={set("city")} error={errors.city} placeholder="San Francisco, CA" />
              <div className="sm:col-span-2">
                <Input label="Residential Address" value={form.address} onChange={set("address")} error={errors.address} placeholder="123 Bark Avenue, Apt 4B" />
              </div>
            </div>
          )}

          {step === 1 && (
            <div className="mt-7 grid gap-4 sm:grid-cols-2">
              <Input label="Home type" value={form.homeType} onChange={set("homeType")} error={errors.homeType} placeholder="Single Family House" />
              <Input label="Time at home" value={form.timeAtHome} onChange={set("timeAtHome")} error={errors.timeAtHome} placeholder="Full-time / WFH" />
              <div className="sm:col-span-2">
                <Input label="Pet experience" value={form.experience} onChange={set("experience")} error={errors.experience} placeholder="Owned two dogs previously" />
              </div>
              <div className="sm:col-span-2">
                <Input label="Other pets or children (optional)" value={form.household} onChange={set("household")} placeholder="No other pets, 1 child age 8" />
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="mt-7">
              {/* What is shown here is what gets frozen onto the application
                  row. Editing your profile later will not change it, which is
                  why it is worth reading before submitting. */}
              <p className="text-sm text-pw-muted">
                These details are stored with the application exactly as they read now.
                Later profile edits will not change them.
              </p>
              <dl className="mt-5 grid gap-5 sm:grid-cols-2">
                {[
                  ["Name", form.fullName], ["Email", form.email], ["Phone", form.phone],
                  ["Address", form.address], ["City", form.city], ["Home type", form.homeType],
                  ["Time at home", form.timeAtHome], ["Pet experience", form.experience],
                  ["Household", form.household || "—"],
                ].map(([k, v]) => (
                  <div key={k}>
                    <dt className="text-[11px] font-semibold uppercase tracking-wider text-pw-muted">{k}</dt>
                    <dd className="mt-1 text-[15px] text-pw-ink">{v || "—"}</dd>
                  </div>
                ))}
              </dl>
            </div>
          )}

          {submitError && (
            <p role="alert" className="mt-6 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-pw-danger">
              {submitError}
            </p>
          )}

          <div className="mt-8 flex items-center justify-between gap-3 border-t border-pw-stroke/40 pt-6">
            <Button variant="outline" onClick={() => (step === 0 ? navigate(-1) : setStep((s) => s - 1))}>
              Back
            </Button>
            {step < 2 ? (
              <Button onClick={() => validate() && setStep((s) => s + 1)}>Continue</Button>
            ) : (
              <Button disabled={!quizDone || busy} onClick={submit}
                title={quizDone ? undefined : "Complete the eligibility quiz first"}>
                {busy ? "Submitting…" : "Submit Application"}
              </Button>
            )}
          </div>
        </Card>

        <div className="space-y-5">
          <Card className="overflow-hidden">
            <div className="relative aspect-[4/3] bg-pw-sidebar/25 overflow-hidden shrink-0">
              {pet.coverPhotoPath && (
                <img src={`${API_BASE}/files/${pet.coverPhotoPath}`} alt={pet.name}
                  className="absolute inset-0 h-full w-full object-cover" />
              )}
              <span className="absolute right-3 top-3"><StatusBadge status={pet.status} /></span>
            </div>
            <div className="p-5">
              <h2 className="text-xl font-semibold">{pet.name}</h2>
              <p className="mt-1 text-sm text-pw-muted">
                {pet.breed} · {pet.sex.charAt(0) + pet.sex.slice(1).toLowerCase()}
              </p>
              <div className="mt-4 grid grid-cols-2 gap-3">
                {[["Age", `${pet.ageValue} ${pet.ageUnit.toLowerCase()}`],
                  ["Size", pet.size.charAt(0) + pet.size.slice(1).toLowerCase()]].map(([k, v]) => (
                  <div key={k} className="rounded-2xl bg-pw-input p-3">
                    <p className="text-[10px] font-semibold uppercase tracking-wider text-pw-muted">{k}</p>
                    <p className="mt-0.5 text-sm font-medium text-pw-ink">{v}</p>
                  </div>
                ))}
              </div>
              <Link to={`/app/pets/${pet.id}`}
                className="mt-4 block rounded-pill bg-pw-input py-2.5 text-center text-sm font-medium text-pw-button hover:bg-pw-stroke/40">
                View Full Profile
              </Link>
            </div>
          </Card>

          <Card tinted className="p-5">
            <p className="flex items-center gap-2 text-sm font-semibold text-pw-button">
              <FiShield className="h-4 w-4" /> Safe Adoption Guarantee
            </p>
            <p className="mt-2 text-sm leading-relaxed text-pw-body">
              Your application goes directly to the shelter's intake team. Typical
              response time is 2–3 business days.
            </p>
          </Card>
        </div>
      </div>

      <EligibilityQuizModal
        open={quizOpen}
        onClose={() => setQuizOpen(false)}
        onComplete={(a) => setAttempt(a)}
      />
    </AdopterLayout>
  );
}
