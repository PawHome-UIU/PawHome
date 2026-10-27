import { useState } from "react";
import { useLocation } from "react-router-dom";
import { FiInfo, FiUploadCloud, FiUser } from "react-icons/fi";
import AdminLayout from "../components/admin/AdminLayout";
import StaffLayout from "../components/staff/StaffLayout";
import AdopterLayout from "../components/adopter/AdopterLayout";
import Card from "../components/ui/Card";
import Button from "../components/ui/Button";
import Input from "../components/ui/Input";
import Tabs from "../components/ui/Tabs";
import SuccessModal from "../components/ui/SuccessModal";
import { passwordError } from "../lib/passwordPolicy";
import PasswordChecklist from "../components/auth/PasswordChecklist";
import { useAuth } from "../context/AuthContext";
import api from "../api/client";
import { apiError } from "../lib/apiError";
import { API_BASE } from "../lib/config";

/**
 * Account settings, shared by all three portals.
 *
 * THIS PAGE USED TO SAVE NOTHING. Every form ran
 * `onSubmit={(e) => { e.preventDefault(); setSaved("profile"); }}` -- it
 * showed "Profile saved" and posted nowhere, and there was no endpoint it
 * could have posted to. It was the one screen in the application that told a
 * person something had happened when nothing had.
 *
 * THREE FIELDS FROM THE MOCK ARE GONE, each for its own reason:
 *
 *   Email     changing it has to re-verify the new address, or an account
 *             moves to an inbox nobody proved they own while email_verified
 *             still says TRUE about the old one. That is the whole
 *             verification flow, not a text box. Shown, read-only.
 *   City      users has no such column, and an adoption application already
 *             carries its own copy of the applicant's address -- on purpose,
 *             so a reviewer sees what was said when it was submitted.
 *   About you likewise no column, and nothing anywhere reads it.
 *
 * Two of the four notification switches are gone as well: "New matches" and
 * "Newsletter" were toggles for mail this system does not send. What replaced
 * them is a switch for the vaccination reminders, which HealthReminderJob has
 * been sending on a schedule with no way to stop it -- see V10.
 */
const LAYOUTS = {
  "/admin": AdminLayout,
  "/staff": StaffLayout,
  "/app": AdopterLayout,
};

function Section({ title, description, children }) {
  return (
    <Card className="p-7">
      <h2 className="text-lg font-semibold">{title}</h2>
      {description && (
        <p className="mt-1.5 text-sm leading-relaxed text-pw-muted">{description}</p>
      )}
      <div className="mt-6">{children}</div>
    </Card>
  );
}

function Toggle({ label, hint, checked, onChange, disabled }) {
  return (
    <label className={`flex items-start justify-between gap-6 py-3.5 ${disabled ? "opacity-60" : ""}`}>
      <span>
        <span className="block text-[15px] text-pw-ink">{label}</span>
        {hint && <span className="mt-0.5 block text-sm text-pw-muted">{hint}</span>}
      </span>
      <span className="relative inline-flex shrink-0">
        <input
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className="peer sr-only"
        />
        <span className="h-7 w-12 rounded-pill bg-pw-stroke transition-colors peer-checked:bg-pw-button peer-focus-visible:ring-2 peer-focus-visible:ring-pw-button/40 peer-focus-visible:ring-offset-2" />
        <span className="pointer-events-none absolute left-1 top-1 h-5 w-5 rounded-full bg-white transition-transform peer-checked:translate-x-5" />
      </span>
    </label>
  );
}

export default function SettingsPage() {
  const { pathname } = useLocation();
  const prefix = Object.keys(LAYOUTS).find((p) => pathname.startsWith(p)) ?? "/app";
  const Layout = LAYOUTS[prefix];

  const { user, applyUser, login } = useAuth();
  const [tab, setTab] = useState("profile");
  const [saved, setSaved] = useState(null);

  return (
    <Layout title="Settings" subtitle="Your account, how you sign in, and what we email you about.">
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { key: "profile", label: "Profile" },
          { key: "security", label: "Security" },
          { key: "notifications", label: "Notifications" },
        ]}
      />

      <div className="mt-6 max-w-3xl">
        {/* KEYED ON THE ACCOUNT, which is what lets the forms below seed
            themselves from it with a plain useState initialiser.
            On a hard refresh AuthContext has no user until /auth/me answers,
            so the fields have to follow it -- and the obvious way to do that,
            an effect that copies user into state, sets state synchronously
            inside an effect body, which this codebase's lint rule forbids and
            which would also wipe anything half-typed every time a re-render
            handed back an equal-but-new user object. A key turns "follow the
            account" into a remount, which is what it actually is. */}
        <AccountPanels
          key={user?.id ?? "anon"}
          user={user}
          applyUser={applyUser}
          login={login}
          tab={tab}
          onSaved={setSaved}
        />
      </div>

      <SuccessModal
        open={saved != null}
        onClose={() => setSaved(null)}
        title={saved === "password" ? "Password updated" : "Profile saved"}
        message={
          saved === "password"
            ? "You are still signed in here. Every other session has been ended — anything else signed in will need the new password."
            : "Shelters will see these details on anything you send from now on."
        }
      />
    </Layout>
  );
}

function AccountPanels({ user, applyUser, login, tab, onSaved }) {
  const [profile, setProfile] = useState({
    fullName: user?.fullName ?? "", phone: user?.phone ?? "",
    jobTitle: user?.jobTitle ?? "",
  });
  const [profileBusy, setProfileBusy] = useState(false);
  const [profileError, setProfileError] = useState("");

  const [pw, setPw] = useState({ current: "", next: "", confirm: "" });
  const [pwErrors, setPwErrors] = useState({});
  const [pwBusy, setPwBusy] = useState(false);

  const [notify, setNotify] = useState({
    notifyApplications: user?.notifyApplications ?? true,
    notifyBoarding: user?.notifyBoarding ?? true,
    notifyHealth: user?.notifyHealth ?? true,
    notifyEmail: user?.notifyEmail ?? true,
  });
  const [notifyBusy, setNotifyBusy] = useState(false);
  const [avatarBusy, setAvatarBusy] = useState(false);

  const set = (k) => (e) => setProfile((f) => ({ ...f, [k]: e.target.value }));

  async function saveProfile(e) {
    e.preventDefault();
    setProfileError("");
    if (!profile.fullName.trim()) {
      setProfileError("A name is required.");
      return;
    }
    setProfileBusy(true);
    try {
      const { data } = await api.put("/me", {
        fullName: profile.fullName.trim(),
        phone: profile.phone.trim(),
        jobTitle: profile.jobTitle.trim(),
      });
      applyUser(data);
      onSaved("profile");
    } catch (err) {
      setProfileError(apiError(err, "Could not save that."));
    } finally {
      setProfileBusy(false);
    }
  }

  async function uploadAvatar(e) {
    const file = e.target.files?.[0];
    // Cleared immediately so picking the same file twice still fires a change
    // event, otherwise a failed upload cannot be retried.
    e.target.value = "";
    if (!file) return;
    const body = new FormData();
    body.append("file", file);
    setAvatarBusy(true);
    setProfileError("");
    try {
      const { data } = await api.post("/me/avatar", body);
      applyUser(data);
    } catch (err) {
      setProfileError(apiError(err, "Could not upload that picture."));
    } finally {
      setAvatarBusy(false);
    }
  }

  async function savePassword(e) {
    e.preventDefault();
    const next = {};
    if (!pw.current) next.current = "Enter your current password.";
    // The same rules as registration and reset, from the same module.
    const problem = passwordError(pw.next);
    if (problem) next.next = problem;
    if (pw.next && pw.next === pw.current) next.next = "That is your current password.";
    if (pw.confirm !== pw.next) next.confirm = "The two passwords do not match.";
    setPwErrors(next);
    if (Object.keys(next).length) return;

    setPwBusy(true);
    try {
      const { data } = await api.post("/me/password", {
        currentPassword: pw.current,
        newPassword: pw.next,
      });
      // THE NEW TOKEN HAS TO BE STORED. Changing the password retires every
      // token issued before it, including the one that just made this call --
      // ignoring the body would log this tab out on its very next request.
      login(data.token, data.user);
      setPw({ current: "", next: "", confirm: "" });
      onSaved("password");
    } catch (err) {
      // The server's answer lands on the field it is about -- a wrong current
      // password is the common case and belongs next to that box.
      setPwErrors({ current: apiError(err, "Could not change your password.") });
    } finally {
      setPwBusy(false);
    }
  }

  /**
   * Saved on every flip, with no Save button.
   *
   * The old page claimed "Saved automatically" beside switches that saved
   * nothing. Now it is true, which means the claim has to be earned on every
   * toggle rather than at the bottom of a form nobody scrolls to.
   */
  async function flip(key, value) {
    const next = { ...notify, [key]: value };
    setNotify(next);
    setNotifyBusy(true);
    try {
      const { data } = await api.put("/me/preferences", next);
      applyUser(data);
    } catch {
      // Put it back. A switch that stays where it was put but did not save is
      // worse than one that visibly refuses.
      setNotify(notify);
    } finally {
      setNotifyBusy(false);
    }
  }

  return (
    <>
      {tab === "profile" && (
        <Section
          title="Profile"
          description="Shelters see your name and contact details on any application or boarding request you send."
        >
          {profileError && (
            <p role="alert" className="mb-5 rounded-card bg-rose-50 px-4 py-3 text-sm text-pw-danger">
              {profileError}
            </p>
          )}

          <div className="mb-6 flex flex-wrap items-center gap-5 border-b border-pw-stroke/40 pb-6">
            <span className="flex h-20 w-20 shrink-0 items-center justify-center overflow-hidden rounded-full bg-pw-input text-pw-button">
              {user?.avatarPath
                ? <img src={`${API_BASE}/files/${user.avatarPath}`} alt=""
                    className="h-full w-full object-cover" />
                : <FiUser className="h-8 w-8" />}
            </span>
            <div>
              <label className={`inline-flex cursor-pointer items-center gap-2 rounded-pill border border-pw-stroke/60
                px-5 py-2.5 text-sm font-medium text-pw-button transition-colors hover:border-pw-button
                ${avatarBusy ? "pointer-events-none opacity-60" : ""}`}>
                <FiUploadCloud className="h-4 w-4" />
                {avatarBusy ? "Uploading…" : "Change picture"}
                <input type="file" accept="image/*" className="sr-only"
                  onChange={uploadAvatar} disabled={avatarBusy} />
              </label>
              <p className="mt-2 text-sm text-pw-muted">JPG, PNG or WebP.</p>
            </div>
          </div>

          <form onSubmit={saveProfile} noValidate>
            <div className="grid gap-4 sm:grid-cols-2">
              <Input label="Full name" required value={profile.fullName}
                onChange={set("fullName")} />
              {/* Read-only, and said out loud rather than silently disabled.
                  Changing an address has to re-verify it. */}
              <Input label="Email" type="email" value={user?.email ?? ""} readOnly
                hint="Contact an administrator to change this." />
            </div>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <Input label="Phone" type="tel" value={profile.phone}
                onChange={set("phone")} placeholder="+1 (555) 012-3456" />
              {user?.role === "STAFF" && (
                <Input label="Job title" value={profile.jobTitle}
                  onChange={set("jobTitle")} placeholder="Adoption Coordinator" />
              )}
            </div>
            <div className="mt-6 flex justify-end border-t border-pw-stroke/40 pt-5">
              <Button type="submit" disabled={profileBusy}>
                {profileBusy ? "Saving…" : "Save changes"}
              </Button>
            </div>
          </form>
        </Section>
      )}

      {tab === "security" && (
        <Section title="Password" description="You stay signed in here. Every other device is signed out immediately.">
          <form onSubmit={savePassword} noValidate>
            <Input
              label="Current password"
              type="password"
              value={pw.current}
              onChange={(e) => setPw((p) => ({ ...p, current: e.target.value }))}
              error={pwErrors.current}
            />
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <div>
                <Input
                  label="New password"
                  type="password"
                  value={pw.next}
                  onChange={(e) => setPw((p) => ({ ...p, next: e.target.value }))}
                  error={pwErrors.next}
                />
                <PasswordChecklist password={pw.next} />
              </div>
              <Input
                label="Confirm new password"
                type="password"
                value={pw.confirm}
                onChange={(e) => setPw((p) => ({ ...p, confirm: e.target.value }))}
                error={pwErrors.confirm}
              />
            </div>

            <div className="mt-6 flex gap-3 rounded-card bg-pw-input p-4">
              <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-pw-button" />
              <p className="text-sm leading-relaxed text-pw-body">
                Length is what makes a password hard to guess. Four unrelated
                words beat eight characters of punctuation, and are easier to
                remember.
              </p>
            </div>

            <div className="mt-6 flex justify-end border-t border-pw-stroke/40 pt-5">
              <Button type="submit" disabled={pwBusy}>
                {pwBusy ? "Updating…" : "Update password"}
              </Button>
            </div>
          </form>
        </Section>
      )}

      {tab === "notifications" && (
        <Section
          title="Notifications"
          description="The bell in the header always shows everything. These control what also reaches your inbox."
        >
          <div className="divide-y divide-pw-stroke/30">
            <Toggle
              label="Application updates"
              hint="When an application you sent is approved or declined."
              checked={notify.notifyApplications} disabled={notifyBusy}
              onChange={(v) => flip("notifyApplications", v)}
            />
            <Toggle
              label="Boarding updates"
              hint="Decisions on your requests, and notes while a pet is staying."
              checked={notify.notifyBoarding} disabled={notifyBusy}
              onChange={(v) => flip("notifyBoarding", v)}
            />
            <Toggle
              label="Vaccination reminders"
              hint="When one of your pets has a shot due, or an overdue one."
              checked={notify.notifyHealth} disabled={notifyBusy}
              onChange={(v) => flip("notifyHealth", v)}
            />
            <Toggle
              label="Email me as well"
              hint="Off means the bell only — nothing reaches your inbox."
              checked={notify.notifyEmail} disabled={notifyBusy}
              onChange={(v) => flip("notifyEmail", v)}
            />
          </div>

          <p className="mt-6 border-t border-pw-stroke/40 pt-5 text-sm text-pw-muted">
            Saved as you change them.
          </p>
        </Section>
      )}
    </>
  );
}
