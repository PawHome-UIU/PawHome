import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  FiClock, FiCheckCircle, FiXCircle, FiRefreshCw, FiSearch,
} from "react-icons/fi";
import StaffLayout from "../../components/staff/StaffLayout";
import StatTile from "../../components/admin/StatTile";
import DataTable from "../../components/admin/DataTable";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Tabs from "../../components/ui/Tabs";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";

/**
 * The review queue.
 *
 * READS THE REAL DATABASE, and is scoped server-side: /api/v1/staff/applications
 * joins through the pet to the shelter, so a staff member sees applications for
 * their own animals and nobody else's. There is no shelter filter in this file
 * and there must not be one -- a filter in a component is a gate anyone walks
 * around with devtools.
 */
const BADGE = "inline-flex h-6 items-center justify-center whitespace-nowrap rounded-pill px-2.5 "
            + "text-[11px] font-semibold leading-none";

/**
 * The score as a fraction, not a bare number.
 *
 * "72" means nothing on its own: the maximum moves whenever an admin adds or
 * archives a question, so the same 72 can be a pass one week and a fail the
 * next. The tint follows the server's own pass/fail rather than a threshold
 * invented here, which would be a second opinion free to disagree with the one
 * that actually decided.
 */
function ScorePill({ score, max, passed }) {
  const tone = passed === true ? "bg-green-50 text-green-700"
    : passed === false ? "bg-rose-50 text-rose-700"
    : "bg-pw-input text-pw-body";
  return (
    <span className={`${BADGE} tabular-nums ${tone}`}>
      {score}{max != null ? `/${max}` : ""}
    </span>
  );
}

const STATUS_TONE = {
  PENDING: "bg-amber-50 text-amber-700",
  APPROVED: "bg-green-50 text-green-700",
  REJECTED: "bg-rose-50 text-pw-danger",
  WITHDRAWN: "bg-pw-input text-pw-muted",
};

const dateOf = (iso) => new Date(iso).toLocaleDateString(undefined,
  { year: "numeric", month: "short", day: "numeric" });

export default function ApplicationsPage() {
  const [tab, setTab] = useState("PENDING");
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState([]);
  const [counts, setCounts] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const [list, tiles] = await Promise.all([
        api.get("/staff/applications"),
        api.get("/staff/applications/counts"),
      ]);
      setRows(list.data);
      setCounts(tiles.data);
    } catch (err) {
      setLoadError(apiError(err, "Could not load the review queue."));
    } finally {
      setLoading(false);
    }
  }, []);

  // Inline on first load so no state is set synchronously in the effect body.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [list, tiles] = await Promise.all([
          api.get("/staff/applications"),
          api.get("/staff/applications/counts"),
        ]);
        if (cancelled) return;
        setRows(list.data);
        setCounts(tiles.data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load the review queue."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((a) => {
      if (tab !== "ALL" && a.status !== tab) return false;
      if (q && ![a.applicantName, a.applicantEmail, a.petName, a.applicantCity]
            .some((v) => v?.toLowerCase().includes(q))) return false;
      return true;
    });
  }, [rows, tab, query]);

  return (
    <StaffLayout
      title="Adoption Applications"
      subtitle={loading ? "Loading…"
        : `${counts?.pending ?? 0} waiting for a decision`}
      searchValue={query}
      onSearchChange={setQuery}
      search="Search by applicant, pet or city…"
      actions={
        <Button variant="outline" size="sm" onClick={load} disabled={loading}>
          <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
        </Button>
      }
    >
      <div className="grid gap-5 sm:grid-cols-3">
        <StatTile icon={FiClock} value={String(counts?.pending ?? "—")} label="Pending Review" tone="warning" />
        <StatTile icon={FiCheckCircle} value={String(counts?.approved ?? "—")} label="Approved" tone="good" />
        <StatTile icon={FiXCircle} value={String(counts?.rejected ?? "—")} label="Declined" />
      </div>

      <div className="mt-6">
        <Tabs
          value={tab}
          onChange={setTab}
          className="mb-5"
          tabs={[
            { key: "PENDING", label: "Pending", count: counts?.pending },
            { key: "APPROVED", label: "Approved", count: counts?.approved },
            { key: "REJECTED", label: "Declined", count: counts?.rejected },
            { key: "WITHDRAWN", label: "Withdrawn", count: counts?.withdrawn },
            { key: "ALL", label: "All", count: counts?.total },
          ]}
        />

        {loadError ? (
          <Card className="px-6 py-14 text-center">
            <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
            <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
          </Card>
        ) : loading ? (
          <Card className="px-6 py-16 text-center">
            <p className="text-[15px] text-pw-muted">Loading applications…</p>
          </Card>
        ) : visible.length === 0 ? (
          <Card className="px-6 py-16 text-center">
            <p className="text-[15px] font-medium text-pw-ink">
              {query.trim() ? `Nobody matches “${query.trim()}”.`
                : tab === "PENDING" ? "Nothing is waiting for a decision."
                : "No applications with that status."}
            </p>
            {query.trim() && (
              <Button size="sm" variant="outline" className="mt-5" onClick={() => setQuery("")}>
                <FiSearch className="h-4 w-4" /> Clear search
              </Button>
            )}
          </Card>
        ) : (
          <DataTable
            title={tab === "ALL" ? "All applications" : `${tab.charAt(0)}${tab.slice(1).toLowerCase()} applications`}
            resetKey={`${tab}|${query}`}
            pageSize={6}
            columns={[
              { key: "applicant", label: "Applicant" },
              { key: "pet", label: "Pet Interested" },
              { key: "submitted", label: "Date Submitted" },
              { key: "score", label: "Quiz Score" },
              { key: "status", label: "Status" },
              { key: "action", label: "", align: "right", className: "w-px whitespace-nowrap" },
            ]}
            rows={visible}
            render={(row, key) => {
              if (key === "applicant") {
                return (
                  <span className="flex items-center gap-3">
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-pw-input text-xs font-semibold text-pw-button">
                      {row.applicantName.split(" ").map((w) => w[0]).join("").slice(0, 2)}
                    </span>
                    <span>
                      <span className="block font-medium text-pw-ink">{row.applicantName}</span>
                      <span className="block text-xs text-pw-muted">{row.applicantCity}</span>
                    </span>
                  </span>
                );
              }
              if (key === "pet") {
                return (
                  <span>
                    <span className="block font-medium text-pw-button">{row.petName}</span>
                    <span className="block text-xs text-pw-muted">{row.petBreed}</span>
                  </span>
                );
              }
              if (key === "submitted") return dateOf(row.submittedAt);
              if (key === "score") {
                return <ScorePill score={row.quizScore} max={row.quizMaxScore} passed={row.quizPassed} />;
              }
              if (key === "status") {
                return <span className={`${BADGE} ${STATUS_TONE[row.status]}`}>{row.status}</span>;
              }
              if (key === "action") {
                return (
                  <Button as={Link} to={`/staff/applications/${row.id}`} size="sm"
                    variant={row.status === "PENDING" ? "primary" : "outline"}
                    className="!h-8 !w-[86px] !px-0">
                    {row.status === "PENDING" ? "Review" : "View"}
                  </Button>
                );
              }
              return row[key];
            }}
          />
        )}
      </div>
    </StaffLayout>
  );
}
