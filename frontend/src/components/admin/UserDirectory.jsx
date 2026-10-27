import { useMemo, useState } from "react";
import { FiSearch, FiX, FiEdit2 } from "react-icons/fi";
import Card from "../ui/Card";
import Button from "../ui/Button";
import Tabs from "../ui/Tabs";
import Dropdown from "../ui/Dropdown";
import DataTable from "./DataTable";

/**
 * Who is on the platform, split into the two groups that have nothing to do
 * with each other.
 *
 * ALMOST READ-ONLY, and the exception is the point. This panel answers "who
 * are our adopters" and "who works at Harbor House" -- questions you ask far
 * more often than you approve anybody -- so it carries none of the management
 * table's buttons and cannot deactivate, reject or approve a soul.
 *
 * The one action it does carry is changing a staff member's shelter, because
 * that is the question this view raises and the table above cannot answer:
 * you notice somebody is filed under the wrong shelter precisely when you are
 * reading a shelter's roster. It opens the same modal the management table
 * uses, so there is one confirmation, one warning and one request behind both.
 *
 * It reads the rows the page already loaded. A second endpoint would return
 * the same records and could disagree with the table above it.
 */
const STATUS_FILTERS = ["Active", "Unconfirmed email", "Deactivated"];

// Same pill shape as the table above, kept in step by hand -- two components
// is not enough to justify a third file for six lines of class names.
// whitespace-nowrap matters with the fixed height: a two-word label like
// "Multiple choice" wraps inside a h-6 pill and spills out of it.
const BADGE = "inline-flex h-6 items-center justify-center whitespace-nowrap rounded-pill px-2.5 "
            + "text-[11px] font-semibold leading-none";

function statusOf(u) {
  if (!u.active) return "Deactivated";
  if (!u.emailVerified) return "Unconfirmed email";
  if (u.approvalStatus === "PENDING") return "Awaiting approval";
  if (u.approvalStatus === "REJECTED") return "Rejected";
  return "Active";
}

const STATUS_TONE = {
  "Active": "bg-green-50 text-green-700",
  "Unconfirmed email": "bg-sky-50 text-sky-700",
  "Awaiting approval": "bg-amber-50 text-amber-700",
  "Rejected": "bg-rose-50 text-pw-danger",
  "Deactivated": "bg-pw-input text-pw-muted",
};

export default function UserDirectory({ rows, onMoveShelter }) {
  const [part, setPart] = useState("adopters");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState(null);
  const [shelter, setShelter] = useState(null);

  const adopters = useMemo(() => rows.filter((u) => u.role === "ADOPTER"), [rows]);
  const staff = useMemo(() => rows.filter((u) => u.role === "STAFF"), [rows]);

  // Only the shelters that actually have someone in them. A filter listing
  // shelters with no staff is a list of dead ends.
  const shelterNames = useMemo(
    () => [...new Set(staff.map((u) => u.shelterName).filter(Boolean))].sort(),
    [staff]);

  const source = part === "adopters" ? adopters : staff;

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return source.filter((u) => {
      if (status && statusOf(u) !== status) return false;
      if (part === "staff" && shelter && u.shelterName !== shelter) return false;
      if (q && ![u.fullName, u.email, u.shelterName, u.jobTitle]
            .some((v) => v?.toLowerCase().includes(q))) return false;
      return true;
    });
  }, [source, part, query, status, shelter]);

  const columns = part === "adopters"
    ? [
        { key: "fullName", label: "Adopter" },
        { key: "status", label: "Status" },
        { key: "createdAt", label: "Joined", align: "right" },
      ]
    : [
        { key: "fullName", label: "Staff member" },
        { key: "shelterName", label: "Shelter" },
        { key: "jobTitle", label: "Job title" },
        { key: "status", label: "Status" },
      ];

  // Switching part resets the filters that do not carry over. Doing it in the
  // handler rather than an effect keeps it to one render, and a shelter filter
  // left applied to the adopter list would silently empty it -- adopters have
  // no shelter at all.
  function switchPart(next) {
    setPart(next);
    setStatus(null);
    setShelter(null);
  }

  const filtered = query.trim() || status || shelter;

  return (
    <Card className="mt-8 overflow-visible">
      {/* The panel's header, in the design's filled purple -- the same bar as
          Modal, PanelCard and the table's own title strip. rounded-t-card
          because the Card around it must stay overflow-visible for the filter
          menus, so this corner is not clipped for us. */}
      <div className="flex flex-wrap items-start justify-between gap-4 rounded-t-card bg-pw-sidebar px-5 py-4">
        <div>
          <h2 className="text-[15px] font-semibold text-white">Directory</h2>
          <p className="mt-1 text-sm text-white/85">
            Everyone on the platform, to look up by shelter or by name.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <label className="relative">
            <span className="sr-only">Search the directory</span>
            <FiSearch className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-pw-muted" />
            {/* White, not the pale pw-input fill: on purple that tint is
                barely a field at all. */}
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={part === "adopters" ? "Search adopters…" : "Search staff…"}
              className="h-9 w-56 rounded-pill bg-white pl-10 pr-4 text-sm text-pw-ink placeholder:text-pw-muted focus:outline-none focus:ring-2 focus:ring-white/60"
            />
          </label>

          {part === "staff" && (
            <Dropdown
              variant="bar" align="right" label="All shelters" anyLabel="All shelters"
              value={shelter} onChange={setShelter} options={shelterNames}
              buttonClassName="!border-transparent"
            />
          )}

          <Dropdown
            variant="bar" align="right" label="Any status" anyLabel="Any status"
            value={status} onChange={setStatus} options={STATUS_FILTERS}
            buttonClassName="!border-transparent"
          />
        </div>
      </div>

      {/* The tabs stay on white, below the purple. Put on the bar itself they
          would be pale-on-purple, and the active underline is the one thing
          here that has to stay obvious. */}
      <div className="border-b border-pw-stroke/40 px-5 pt-4">
        <Tabs
          value={part}
          onChange={switchPart}
          tabs={[
            { key: "adopters", label: "Adopters", count: adopters.length },
            { key: "staff", label: "Staff", count: staff.length },
          ]}
          className="border-b-0"
        />
      </div>

      {visible.length === 0 ? (
        <div className="px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-ink">
            {filtered
              ? "Nobody here matches that."
              : part === "adopters"
                ? "No adopters have signed up yet."
                : "No staff accounts yet."}
          </p>
          {filtered && (
            <Button size="sm" variant="outline" className="mt-5"
              onClick={() => { setQuery(""); setStatus(null); setShelter(null); }}>
              <FiX className="h-4 w-4" /> Clear filters
            </Button>
          )}
        </div>
      ) : (
        // No title or Card of its own -- DataTable is nested inside this panel,
        // and a second bordered box around it would read as a separate section.
        //
        // The clipping is here rather than on the Card because the Card must
        // stay overflow-visible: the two filter dropdowns in its header open
        // downward and overflow-hidden would cut their menus off. This wrapper
        // rounds and clips only the table, so the last row follows the panel's
        // bottom corners instead of squaring them off.
        <div className="overflow-hidden rounded-b-card [&>div]:rounded-none [&>div]:border-0 [&>div]:shadow-none">
          <DataTable
            resetKey={`${query}|${status ?? "all"}`}
            pageSize={6}
            columns={columns}
            rows={visible}
            render={(row, key) => {
              if (key === "fullName") {
                return (
                  <div className="group flex items-center gap-3">
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-pw-input text-xs font-semibold text-pw-button">
                      {row.fullName.split(" ").map((w) => w[0]).join("").slice(0, 2)}
                    </span>
                    {/* flex-1 so the button below lands on the right edge of
                        the column instead of immediately after the text. Names
                        are all different lengths; a button that follows them
                        is a ragged line of pencils down the table. */}
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium text-pw-ink">{row.fullName}</span>
                      <span className="block truncate text-xs text-pw-muted">{row.email}</span>
                    </span>

                    {/* Staff only. An adopter has no shelter_id at all --
                        ck_users_staff_shelter forbids one -- so the button
                        would open a form whose only outcome is a refusal.

                        Always rendered, never hover-only: an action that
                        appears when the pointer arrives is invisible to anyone
                        using a keyboard or a touchscreen. It is quiet until
                        you reach it instead. */}
                    {part === "staff" && onMoveShelter && (
                      <button
                        type="button"
                        onClick={() => onMoveShelter(row)}
                        title="Change shelter"
                        aria-label={`Change ${row.fullName}'s shelter`}
                        className="ml-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-pw-muted transition-colors hover:bg-pw-input hover:text-pw-button focus:outline-none focus-visible:ring-2 focus-visible:ring-pw-button/40"
                      >
                        <FiEdit2 className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </div>
                );
              }

              if (key === "status") {
                const s = statusOf(row);
                return <span className={`${BADGE} ${STATUS_TONE[s]}`}>{s}</span>;
              }

              if (key === "jobTitle" || key === "shelterName") {
                return row[key] ?? <span className="text-pw-muted">—</span>;
              }

              if (key === "createdAt") {
                // Date only. A registration timestamp to the second is noise in
                // a column nobody scans for the minute somebody signed up.
                return (
                  <span className="text-pw-body">
                    {new Date(row.createdAt).toLocaleDateString(undefined,
                      { year: "numeric", month: "short", day: "numeric" })}
                  </span>
                );
              }

              return row[key];
            }}
          />
        </div>
      )}
    </Card>
  );
}
