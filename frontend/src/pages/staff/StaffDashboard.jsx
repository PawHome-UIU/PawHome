import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  FiHeart, FiFileText, FiHome, FiAlertCircle, FiPlus, FiClipboard,
  FiMapPin, FiChevronRight, FiAlertTriangle, FiCheckCircle,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import PanelCard from "../../components/portal/PanelCard";
import StatTile from "../../components/admin/StatTile";
import Button from "../../components/ui/Button";
import api from "../../api/client";
import { useAuth } from "../../context/AuthContext";

const TYPE_TONE = {
  MEDICATION: "bg-pw-input text-pw-button",
  FEEDING: "bg-green-50 text-green-700",
  EXERCISE: "bg-amber-50 text-amber-700",
  WOUND_CARE: "bg-rose-50 text-rose-700",
  GROOMING: "bg-sky-50 text-sky-700",
};

export function TaskTypePill({ type }) {
  return (
    <span className={`rounded-md px-2 py-0.5 text-[10px] font-semibold tracking-wide ${TYPE_TONE[type] ?? TYPE_TONE.MEDICATION}`}>
      {type.replace("_", " ")}
    </span>
  );
}

/** "Duke, Mittens and Daisy" -> "Duke, Mittens and Daisy" when they are real. */
function names(list, limit = 3) {
  const shown = list.slice(0, limit);
  const rest = list.length - shown.length;
  const joined = shown.length < 2
    ? shown.join("")
    : `${shown.slice(0, -1).join(", ")} and ${shown[shown.length - 1]}`;
  return rest > 0 ? `${joined}, and ${rest} more` : joined;
}

/**
 * The three things worth interrupting somebody for, counted from real rows.
 *
 * This panel used to be a hard-coded array -- "Overdue Vaccinations (3) ...
 * Duke, Mittens and Daisy" -- which read as a working alert and named three
 * animals that might not exist at this shelter. A dashboard that invents work
 * is worse than one with an empty panel, because somebody acts on it.
 *
 * An item with a count of zero is dropped rather than shown as "(0)". Nothing
 * needing attention is the good outcome, and it deserves a sentence saying so
 * rather than three green zeroes.
 */
function attentionItems({ vaccinations, boardings, pets }) {
  const items = [];

  const overdue = vaccinations.filter((v) => v.overdue);
  if (overdue.length) {
    items.push({
      dot: "bg-rose-500",
      to: "/staff/care",
      title: `Overdue vaccinations (${overdue.length})`,
      body: `Past their due date: ${names([...new Set(overdue.map((v) => v.petName))])}.`,
    });
  }

  // A stay ends on the day it is collected, so "leaving today" is endDate ==
  // today on a row that is actually in progress. A LocalDate arrives as
  // "YYYY-MM-DD", which is why this compares strings rather than parsing --
  // new Date("2026-09-05") is midnight UTC and shifts the day for anybody west
  // of Greenwich.
  const today = new Date();
  const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  const leaving = boardings.filter((b) => b.status === "ACTIVE" && b.endDate === iso);
  if (leaving.length) {
    items.push({
      dot: "bg-pw-button",
      to: "/staff/boarding",
      title: `Boarding pickups today (${leaving.length})`,
      body: `Due to be collected: ${names(leaving.map((b) => b.petName))}.`,
    });
  }

  // Only pets still on the catalogue. An adopted animal with no photo is not
  // work anybody needs to do.
  const thin = pets.filter((p) =>
    (p.status === "AVAILABLE" || p.status === "PENDING") &&
    (!p.coverPhotoPath || !p.description?.trim()));
  if (thin.length) {
    items.push({
      dot: "bg-pw-muted",
      to: "/staff/pets",
      title: `Profiles missing photos or details (${thin.length})`,
      body: `An animal without a picture is rarely applied for: ${names(thin.map((p) => p.name))}.`,
    });
  }

  return items;
}

export default function StaffDashboard() {
  const { user } = useAuth();
  const [board, setBoard] = useState({ counts: null, tasks: [], logs: [] });
  const [apps, setApps] = useState([]);
  const [pets, setPets] = useState([]);
  const [boardings, setBoardings] = useState([]);
  const [vaccinations, setVaccinations] = useState([]);
  const [shelter, setShelter] = useState(null);
  const [busyId, setBusyId] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [careRes, appRes, petRes, boardRes, vaccRes, shelterRes] =
          await Promise.all([
            api.get("/staff/care"),
            api.get("/staff/applications"),
            api.get("/staff/pets"),
            api.get("/staff/boarding"),
            api.get("/staff/vaccinations"),
            api.get("/shelters/mine"),
          ]);
        if (cancelled) return;
        setBoard(careRes.data);
        setApps(appRes.data);
        setPets(petRes.data);
        setBoardings(boardRes.data);
        setVaccinations(vaccRes.data);
        setShelter(shelterRes.data);
      } catch {
        // A dashboard that renders an error banner instead of the shelter's
        // work is worse than one with empty panels. Every panel below reads
        // as "nothing yet" and the pages behind them report properly.
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /** Ticking here posts, and takes the whole board back. */
  async function complete(id) {
    setBusyId(id);
    try {
      const { data } = await api.post(`/staff/care/tasks/${id}/complete`);
      setBoard(data);
    } catch { /* the checkbox springs back */ }
    finally { setBusyId(null); }
  }

  const today = board.tasks.filter((t) => t.status === "PENDING").slice(0, 4);
  const pendingApps = apps.filter((a) => a.status === "PENDING");
  // The counts endpoint is no longer called: the full list answers the same
  // question AND feeds the pickup alert below, so one request replaces two.
  const activeBoardings = boardings.filter(
    (b) => b.status === "ACCEPTED" || b.status === "ACTIVE").length;
  const attention = attentionItems({ vaccinations, boardings, pets });
  // The most recent adoption this shelter completed. Real, or the card is not
  // rendered -- "Oliver was adopted yesterday" was a sentence about nobody.
  const lastAdoption = apps
    .filter((a) => a.status === "APPROVED" && a.adoptedOn)
    .sort((a, b) => b.adoptedOn.localeCompare(a.adoptedOn))[0];
  const firstName = user?.fullName?.trim().split(/\s+/)[0];

  const when = (iso) => (iso ? new Date(iso).toLocaleString(undefined,
    { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) : "");

  return (
    <StaffLayout
      title={firstName ? `Welcome back, ${firstName}` : "Welcome back"}
      subtitle={
        <span className="inline-flex items-center gap-1.5">
          <FiMapPin className="h-4 w-4 text-pw-button" />
          {shelter?.name ? `${shelter.name} · ` : ""}
          {new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: "numeric" })}
        </span>
      }
      actions={
        <>
          <Button size="sm" to="/staff/pets"><FiPlus className="h-4 w-4" /> Add Pet</Button>
          <Button size="sm" variant="subtle" to="/staff/care"><FiClipboard className="h-4 w-4" /> Log Care Task</Button>
          <Button size="sm" variant="outline" to="/staff/applications">Review Applications</Button>
        </>
      }
    >
      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        {/* Counted, not typed. These read 42 and 18 for every shelter in the
            network before, which is the sort of number people quote in a
            meeting. */}
        {/* ON SITE, not "in care". pets.length is the shelter's own list --
            animals on its books -- and rendering it under "Pets in Care" is
            what made accepting two boarding requests look like it changed
            nothing: a boarded pet belongs to a household and is not on that
            list. shelter.onSiteCount counts who is actually in the building. */}
        <StatTile icon={FiHome} value={shelter?.onSiteCount ?? 0} label="On Site Now" tone="good" />
        <StatTile icon={FiHeart} value={pets.length} label="Our Animals" />
        <StatTile icon={FiFileText} value={pendingApps.length} label="Pending Applications" />
        <StatTile icon={FiHome} value={activeBoardings} label="Active Boardings" />
        {/* OVERDUE, not "due today", because overdue is the number the server
            actually computes -- PENDING with a due time in the past. "Due
            today" would be a different question nothing answers. */}
        <StatTile icon={FiAlertCircle} value={board.counts?.overdue ?? 0}
          label="Overdue Care Tasks" tone="warning" />
      </div>

      <div className="mt-6 grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <PanelCard
            title="Today's Care Tasks"
            action={
              <Link to="/staff/care" className="text-sm font-medium text-white/90 hover:text-white hover:underline">
                View All Schedule
              </Link>
            }
          >
            <ul className="divide-y divide-pw-stroke/30">
              {today.map((t) => (
                <li key={t.id} className="flex flex-wrap items-center gap-4 px-5 py-4">
                  {/* A checkbox, not a button: completing a task is a state the
                      row carries, and screen readers announce it as such. */}
                  <input type="checkbox" checked={false} disabled={busyId === t.id}
                    onChange={() => complete(t.id)}
                    aria-label={`Mark ${t.description} for ${t.petName} done`}
                    className="h-5 w-5 shrink-0 rounded-md border-pw-stroke text-pw-button focus:ring-pw-button/30" />
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-pw-input text-xs font-semibold text-pw-button">
                    {t.petName.slice(0, 2)}
                  </span>
                  <div className="min-w-[190px] flex-1">
                    <p className="text-[15px] font-medium text-pw-ink">
                      {t.petName}{" "}
                      <span className="font-normal text-pw-muted">
                        · {t.assignedToName ?? "anyone on shift"}
                      </span>
                    </p>
                    <p className="mt-1 flex items-center gap-2">
                      <TaskTypePill type={t.taskType} />
                      <span className="text-xs tabular-nums text-pw-muted">{when(t.dueAt)}</span>
                      {t.priority === "CRITICAL" && (
                        <span className="inline-flex items-center gap-1 rounded-md bg-rose-50 px-1.5 py-0.5 text-[10px] font-semibold text-rose-700">
                          <FiAlertTriangle className="h-3 w-3" /> CRITICAL
                        </span>
                      )}
                    </p>
                  </div>
                  <p className="text-sm text-pw-body">{t.description}</p>
                </li>
              ))}
            </ul>
          </PanelCard>

          <PanelCard
            title="Recent Applications"
            action={
              <Link to="/staff/applications" className="text-sm font-medium text-white/90 hover:text-white hover:underline">
                View All ({pendingApps.length})
              </Link>
            }
          >
            <div className="grid gap-4 p-5 sm:grid-cols-2">
              {pendingApps.slice(0, 4).map((a) => (
                <div key={a.id} className="flex items-center gap-3.5 rounded-2xl border border-pw-stroke/40 p-4">
                  <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-pw-input text-sm font-semibold text-pw-button">
                    {a.applicantName.split(" ").map((w) => w[0]).join("")}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[15px] font-medium text-pw-ink">{a.applicantName}</p>
                    <p className="truncate text-sm text-pw-muted">
                      Applying for <span className="text-pw-button">{a.petName}</span>
                    </p>
                    <Link to={`/staff/applications/${a.id}`}
                      className="mt-1 inline-flex items-center gap-0.5 text-sm font-medium text-pw-button hover:underline">
                      Review <FiChevronRight className="h-3.5 w-3.5" />
                    </Link>
                  </div>
                </div>
              ))}
            </div>
          </PanelCard>
        </div>

        <div className="space-y-5">
          <PanelCard title="Needs Attention" icon={FiAlertCircle}>
            {attention.length === 0 ? (
              <p className="px-5 py-10 text-center text-sm text-pw-muted">
                Nothing is overdue and every profile has a photo. Well done.
              </p>
            ) : (
              <ul className="space-y-4 p-5">
                {attention.map((a) => (
                  <li key={a.title} className="flex gap-3">
                    <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${a.dot}`} />
                    <div>
                      {/* Every item links to the page that fixes it. An alert
                          you cannot act on from where you read it is a nag. */}
                      <Link to={a.to}
                        className="text-[15px] font-medium leading-snug text-pw-ink hover:text-pw-button hover:underline">
                        {a.title}
                      </Link>
                      <p className="mt-0.5 text-sm leading-relaxed text-pw-muted">{a.body}</p>
                    </div>
                  </li>
                ))}
              </ul>
            )}
            <div className="border-t border-pw-stroke/30 p-4">
              <Button variant="outline" size="sm" full to="/staff/care">
                Open the care schedule
              </Button>
            </div>
          </PanelCard>

          {lastAdoption && (
            <div className="rounded-card bg-pw-sidebar p-6 text-white">
              <p className="flex items-center gap-2 text-lg font-semibold">
                <FiCheckCircle className="h-5 w-5" /> Adoption completed
              </p>
              <p className="mt-2 text-sm leading-relaxed text-white/85">
                {lastAdoption.petName} went home with {lastAdoption.applicantName} on{" "}
                {new Date(`${lastAdoption.adoptedOn}T00:00:00`).toLocaleDateString(
                  undefined, { month: "long", day: "numeric", year: "numeric" })}.
              </p>
              <Button variant="white" size="sm" className="mt-5"
                to={`/staff/applications/${lastAdoption.id}`}>
                See the application
              </Button>
            </div>
          )}
        </div>
      </div>
    </StaffLayout>
  );
}
