import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  FiUserPlus, FiUsers, FiShield, FiClock, FiCheck, FiX, FiMail,
  FiAlertCircle, FiRefreshCw, FiSend, FiUserCheck, FiUserX, FiRotateCcw, FiHome,
} from "react-icons/fi";
import AdminLayout from "../../components/admin/AdminLayout";
import StatTile from "../../components/admin/StatTile";
import DataTable from "../../components/admin/DataTable";
import Card from "../../components/ui/Card";
import Button from "../../components/ui/Button";
import Tabs from "../../components/ui/Tabs";
import Dropdown from "../../components/ui/Dropdown";
import ActionMenu from "../../components/ui/ActionMenu";
import SuccessModal from "../../components/ui/SuccessModal";
import InviteStaffModal from "../../components/admin/InviteStaffModal";
import RejectStaffModal from "../../components/admin/RejectStaffModal";
import MoveShelterModal from "../../components/admin/MoveShelterModal";
import UserDirectory from "../../components/admin/UserDirectory";
import api from "../../api/client";
import { apiError } from "../../lib/apiError";
import { useAuth } from "../../context/AuthContext";

/**
 * User management, and the staff approval queue.
 *
 * READS THE REAL DATABASE. It used to render a hard-coded array from
 * data/admin.js, which looked exactly like a working queue and was connected to
 * nothing -- so every staff sign-up sat PENDING forever with no way for an
 * admin to see it, let alone act.
 *
 * The three things this screen has to make obvious, because each blocks a
 * login and they are NOT the same problem:
 *
 *   PENDING       an admin has not approved them yet   -> Approve / Reject
 *   INVITED       an invitation is out, unanswered     -> Resend invitation
 *   unverified    they never confirmed their address   -> Resend / Mark verified
 *
 * An account can be stuck on more than one. Showing a single badge would hide
 * half the reason somebody cannot get in.
 *
 * WHY THE COUNTS ARE COMPUTED HERE and not read from GET /admin/users/counts:
 * list() already returns every row, so counting them here cannot disagree with
 * the table underneath. A second request could -- somebody registers between
 * the two calls and the tile says 4 while the tab says 3. That endpoint is for
 * the dashboard, which does not load the rows.
 */
/**
 * Every status pill, one shape.
 *
 * They were five separate class strings, so each one sized itself from its own
 * text: a one-word badge and a two-word badge came out different heights and
 * the text sat a pixel or two off centre. inline-flex + a fixed h-6 + leading-none
 * makes the box the same every time and centres the label inside it.
 */
// whitespace-nowrap matters with the fixed height: a two-word label like
// "Multiple choice" wraps inside a h-6 pill and spills out of it.
const BADGE = "inline-flex h-6 items-center justify-center whitespace-nowrap rounded-pill px-2.5 "
            + "text-[11px] font-semibold leading-none";

/**
 * What the table's header bar says.
 *
 * The tab row sits directly above it, so a fixed "Users" would just repeat the
 * page title back. Naming the active filter instead means the bar earns the
 * space: on a filtered view it is the one line telling you that the six rows
 * below are not everybody.
 */
const TAB_TITLE = {
  all: "All Users",
  pending: "Awaiting Approval",
  invited: "Invited",
  unverified: "Unconfirmed Email",
  inactive: "Deactivated Accounts",
};

const ROLE_TONE = {
  ADMIN: "bg-pw-input text-pw-button",
  STAFF: "bg-sky-50 text-sky-700",
  ADOPTER: "bg-pw-box text-pw-body",
};

export default function UsersPage() {
  const { user: me } = useAuth();

  const [tab, setTab] = useState("all");
  const [role, setRole] = useState(null);
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [busyId, setBusyId] = useState(null);

  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviting, setInviting] = useState(false);
  const [inviteError, setInviteError] = useState("");
  const [rejecting, setRejecting] = useState(null);
  const [moving, setMoving] = useState(null);
  const [movingBusy, setMovingBusy] = useState(false);
  const [moveError, setMoveError] = useState("");
  const [notice, setNotice] = useState(null);

  // The tab row and the table below it. The banner scrolls here, because the
  // four stat tiles between them are usually taller than the rest of the
  // viewport.
  const listRef = useRef(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get("/admin/users");
      setRows(data);
    } catch (err) {
      // Saying so beats an empty table that looks like "no users yet".
      setLoadError(apiError(err, "Could not load users."));
    } finally {
      setLoading(false);
    }
  }, []);

  // The first load runs inline rather than calling load(), so that no state is
  // set synchronously while the effect body is running -- the same shape as
  // SheltersPage. load() stays for Refresh and for reloading after a write.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/admin/users");
        if (!cancelled) setRows(data);
      } catch (err) {
        if (!cancelled) setLoadError(apiError(err, "Could not load users."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /** Run one action, then reload so the row reflects what the server did. */
  async function act(id, path, label, body) {
    setBusyId(id);
    try {
      await api.post(`/admin/users/${id}/${path}`, body);
      await load();
      setNotice({ title: label });
    } catch (err) {
      setNotice({ title: "That didn't work", message: apiError(err) });
    } finally {
      setBusyId(null);
    }
  }

  /**
   * Send an invitation.
   *
   * The modal stays open on failure, still holding what was typed. The common
   * failure is a 409 -- that address is already registered -- and an admin who
   * has to retype the name and shelter to fix one character will not enjoy it.
   */
  async function sendInvite(payload) {
    setInviting(true);
    setInviteError("");
    try {
      const { data } = await api.post("/admin/users/invite", payload);
      setInviteOpen(false);
      await load();
      setNotice({
        title: `Invitation sent to ${data.email}`,
        message: "They have seven days to choose a password. Until they do, "
               + "the account cannot sign in.",
      });
    } catch (err) {
      setInviteError(apiError(err, "That invitation could not be sent."));
    } finally {
      setInviting(false);
    }
  }

  /**
   * The banner's button.
   *
   * It used to only call setTab("pending"), which read as a dead button: the
   * queue it switches to is below four stat tiles and off screen, so clicking
   * changed nothing you could see. It now clears the other filters as well --
   * a leftover search or role filter could hide the very rows it promised --
   * and scrolls the table into view.
   */
  function reviewQueue() {
    setTab("pending");
    setQuery("");
    setRole(null);
    listRef.current?.scrollIntoView({
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "auto" : "smooth",
      block: "start",
    });
  }

  /**
   * Move a staff member to another shelter.
   *
   * Kept out of act() because that one closes nothing and shows a generic
   * "that didn't work": this modal has to stay open on failure, holding the
   * chosen shelter, since the likely refusals -- the shelter went inactive,
   * somebody else already moved them -- are ones the admin fixes by picking a
   * different row in the same dropdown.
   */
  async function moveShelter(shelterId) {
    const target = moving;
    setMovingBusy(true);
    setMoveError("");
    try {
      const { data } = await api.post(`/admin/users/${target.id}/shelter`, { shelterId });
      setMoving(null);
      await load();
      setNotice({
        title: `${data.fullName} moved to ${data.shelterName}`,
        message: "They can see that shelter's pets, applications and boarding "
               + "from their next request, without signing out.",
      });
    } catch (err) {
      setMoveError(apiError(err, "That move could not be made."));
    } finally {
      setMovingBusy(false);
    }
  }

  const pendingCount = rows.filter(
    (u) => u.role === "STAFF" && u.approvalStatus === "PENDING").length;
  const unverifiedCount = rows.filter((u) => !u.emailVerified).length;
  const invitedCount = rows.filter((u) => u.invitePending && !u.emailVerified).length;

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((u) => {
      if (tab === "pending" && !(u.role === "STAFF" && u.approvalStatus === "PENDING")) return false;
      if (tab === "invited" && !(u.invitePending && !u.emailVerified)) return false;
      if (tab === "unverified" && u.emailVerified) return false;
      if (tab === "inactive" && u.active) return false;
      if (role && u.role !== role.toUpperCase()) return false;
      // Name, address and shelter -- the three things an admin has in hand
      // when they arrive here looking for one particular person.
      if (q && ![u.fullName, u.email, u.shelterName]
            .some((v) => v?.toLowerCase().includes(q))) return false;
      return true;
    });
  }, [rows, tab, role, query]);

  return (
    <AdminLayout
      title="Users"
      subtitle="Everyone with an account, across every shelter."
      search="Search by name, email or shelter…"
      searchValue={query}
      onSearchChange={setQuery}
      actions={
        <>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <FiRefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Refresh
          </Button>
          <Dropdown
            variant="bar" align="right" label="All roles" anyLabel="All roles"
            value={role} onChange={setRole}
            options={["Admin", "Staff", "Adopter"]}
          />
          <Button size="sm" onClick={() => { setInviteError(""); setInviteOpen(true); }}>
            <FiUserPlus className="h-4 w-4" /> Invite Staff
          </Button>
        </>
      }
    >
      {/* The queue, said out loud. A count buried in a tab label is easy to
          walk past; people waiting to be let in should not depend on that. */}
      {pendingCount > 0 && (
        <Card className="mb-6 flex flex-wrap items-center gap-4 border-amber-200 bg-amber-50 p-5">
          <FiAlertCircle className="h-5 w-5 shrink-0 text-amber-700" />
          <p className="flex-1 text-[15px] text-amber-900">
            <strong>{pendingCount} shelter staff {pendingCount === 1 ? "account is" : "accounts are"} waiting
            for approval.</strong> They cannot sign in until you approve them.
          </p>
          <Button size="sm" variant="outline" onClick={reviewQueue}>
            Review {pendingCount === 1 ? "it" : "them"}
          </Button>
        </Card>
      )}

      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile icon={FiUsers} value={String(rows.length)} label="Total Users" />
        <StatTile icon={FiShield} value={String(rows.filter((u) => u.role === "STAFF").length)} label="Shelter Staff" />
        <StatTile icon={FiClock} value={String(pendingCount)} label="Awaiting Approval" />
        <StatTile icon={FiMail} value={String(unverifiedCount)} label="Unconfirmed Email" />
      </div>

      <div ref={listRef} className="mt-8 scroll-mt-20">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { key: "all", label: "All", count: rows.length },
            { key: "pending", label: "Awaiting approval", count: pendingCount },
            { key: "invited", label: "Invited", count: invitedCount },
            { key: "unverified", label: "Unconfirmed email", count: unverifiedCount },
            { key: "inactive", label: "Deactivated", count: rows.filter((u) => !u.active).length },
          ]}
        />
      </div>

      {loadError ? (
        <Card className="mt-6 px-6 py-14 text-center">
          <p className="text-[15px] font-medium text-pw-danger">{loadError}</p>
          <Button size="sm" className="mt-5" onClick={load}>Try again</Button>
        </Card>
      ) : loading ? (
        <Card className="mt-6 px-6 py-16 text-center">
          <p className="text-[15px] text-pw-muted">Loading users…</p>
        </Card>
      ) : visible.length === 0 ? (
        <Card className="mt-6 px-6 py-16 text-center">
          <p className="text-[15px] font-medium text-pw-ink">
            {query.trim()
              ? `Nobody matches \u201C${query.trim()}\u201D.`
              : tab === "pending"
                ? "Nobody is waiting for approval."
                : tab === "invited"
                  ? "No invitations are outstanding."
                  : "No users match that filter."}
          </p>
          {query.trim() && (
            <Button size="sm" variant="outline" className="mt-5" onClick={() => setQuery("")}>
              Clear search
            </Button>
          )}
        </Card>
      ) : (
        <div className="mt-6">
          <DataTable
            title={TAB_TITLE[tab]}
            // Filtering while on page 3 would otherwise show page 3 of the
            // filtered list, which is never what anybody just asked for.
            resetKey={`${tab}|${query}`}
            pageSize={6}
            columns={[
              { key: "fullName", label: "User" },
              { key: "role", label: "Role" },
              { key: "shelterName", label: "Shelter" },
              { key: "status", label: "Status" },
              { key: "actions", label: "", align: "right", className: "w-px whitespace-nowrap" },
            ]}
            rows={visible}
            render={(row, key) => {
              if (key === "fullName") {
                return (
                  <div className="flex items-center gap-3">
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-pw-input text-xs font-semibold text-pw-button">
                      {row.fullName.split(" ").map((w) => w[0]).join("").slice(0, 2)}
                    </span>
                    <span>
                      <span className="block font-medium text-pw-ink">{row.fullName}</span>
                      <span className="block text-xs text-pw-muted">{row.email}</span>
                    </span>
                  </div>
                );
              }

              if (key === "role") {
                return (
                  <span className={`${BADGE} ${ROLE_TONE[row.role]}`}>
                    {row.role}
                  </span>
                );
              }

              // ck_users_staff_shelter: only STAFF carry one, so an em dash is
              // the honest rendering of a NULL that must be NULL.
              if (key === "shelterName") {
                return row.shelterName ?? <span className="text-pw-muted">—</span>;
              }

              if (key === "status") {
                // Every blocker, separately. One badge would hide the fact that
                // an account can be stuck on approval AND on verification.
                //
                // INVITED replaces "email unconfirmed" rather than joining it:
                // both are true, and only one of them says what to do next. An
                // invited person was never sent a confirmation link at all --
                // accepting the invitation is what confirms them.
                return (
                  <span className="flex flex-wrap items-center gap-1.5">
                    {!row.active && (
                      <span className={`${BADGE} bg-pw-input text-pw-muted`}>
                        DEACTIVATED
                      </span>
                    )}
                    {row.approvalStatus === "PENDING" && (
                      <span className={`${BADGE} bg-amber-50 text-amber-700`}>
                        AWAITING APPROVAL
                      </span>
                    )}
                    {row.approvalStatus === "REJECTED" && (
                      <span className={`${BADGE} bg-rose-50 text-pw-danger`}>
                        REJECTED
                      </span>
                    )}
                    {!row.emailVerified && row.invitePending && (
                      <span className={`${BADGE} bg-pw-box text-pw-button`}>
                        INVITED
                      </span>
                    )}
                    {!row.emailVerified && !row.invitePending && (
                      <span className={`${BADGE} bg-sky-50 text-sky-700`}>
                        EMAIL UNCONFIRMED
                      </span>
                    )}
                    {row.active && row.emailVerified && row.approvalStatus === "APPROVED" && (
                      <span className={`${BADGE} bg-green-50 text-green-700`}>
                        ACTIVE
                      </span>
                    )}
                  </span>
                );
              }

              if (key === "actions") {
                const busy = busyId === row.id;
                const isMe = me?.id === row.id;
                const invited = row.invitePending && !row.emailVerified;
                const queued = row.role === "STAFF" && row.approvalStatus === "PENDING";
                // Unconfirmed, but not because an invitation is outstanding --
                // an invited account has no confirmation link to resend.
                const unconfirmed = !row.emailVerified && !invited;

                return (
                  // flex-nowrap, and AT MOST TWO inline buttons. Approve and
                  // Reject used to sit one above the other, because a third
                  // button pushed the row past the width the column had and
                  // flex-wrap broke the line between them. A decision offered
                  // as a stacked pair reads as two unrelated actions.
                  <span className="flex flex-nowrap items-center justify-end gap-2">
                    {queued && (
                      <>
                        {/* Identical width, so the pair reads as one choice
                            rather than a big yes and a small no. */}
                        <Button size="sm" disabled={busy}
                          onClick={() => act(row.id, "approve", `${row.fullName} approved`)}
                          className="!h-8 !w-[104px] !px-0 !bg-green-600 hover:!bg-green-700">
                          <FiCheck className="h-3.5 w-3.5" /> Approve
                        </Button>
                        <Button size="sm" variant="outline" disabled={busy}
                          onClick={() => setRejecting(row)}
                          className="!h-8 !w-[104px] !px-0 !border-pw-danger !text-pw-danger">
                          <FiX className="h-3.5 w-3.5" /> Reject
                        </Button>
                      </>
                    )}

                    {/* The one-click fix for the most common complaint -- but
                        never alongside Approve/Reject, where it would be the
                        third button and the one that breaks the row. On those
                        rows it moves into the menu instead. */}
                    {unconfirmed && !queued && (
                      <Button size="sm" variant="outline" disabled={busy}
                        onClick={() => act(row.id, "resend-verification", "Confirmation link sent")}
                        className="!h-8 !w-[124px] !px-0">
                        <FiMail className="h-3.5 w-3.5" /> Resend link
                      </Button>
                    )}

                    <ActionMenu
                      disabled={busy}
                      label={`Actions for ${row.fullName}`}
                      items={[
                        invited && {
                          label: "Resend invitation", icon: FiSend,
                          // issue() kills the previous link first, so there is
                          // never more than one live invitation for an account.
                          onClick: () => act(row.id, "resend-invite", "Invitation sent again"),
                        },
                        unconfirmed && queued && {
                          label: "Resend confirmation link", icon: FiMail,
                          onClick: () => act(row.id, "resend-verification", "Confirmation link sent"),
                        },
                        !row.emailVerified && {
                          label: "Mark email confirmed", icon: FiUserCheck,
                          onClick: () => act(row.id, "verify-email",
                            `${row.fullName}'s address marked confirmed`),
                        },
                        row.role === "STAFF" && row.approvalStatus === "REJECTED" && {
                          label: "Approve after all", icon: FiRotateCcw,
                          onClick: () => act(row.id, "approve", `${row.fullName} approved`),
                        },
                        // STAFF only: ck_users_staff_shelter forbids a
                        // shelter_id on anyone else, so the server would
                        // refuse it and the item would be decoration.
                        row.role === "STAFF" && {
                          label: "Move to another shelter", icon: FiHome,
                          onClick: () => { setMoveError(""); setMoving(row); },
                        },
                        // Hidden on your own row rather than shown and refused.
                        // The server rejects it too -- the last admin switching
                        // themselves off locks everybody out of this portal --
                        // but an option that always fails is not worth showing.
                        row.active && !isMe && {
                          label: "Deactivate account", icon: FiUserX, tone: "danger",
                          onClick: () => act(row.id, "deactivate", `${row.fullName} deactivated`),
                        },
                        !row.active && {
                          label: "Reactivate account", icon: FiUserCheck,
                          onClick: () => act(row.id, "activate", `${row.fullName} reactivated`),
                        },
                      ]}
                    />
                  </span>
                );
              }

              return row[key];
            }}
          />
        </div>
      )}

      {/* A lookup, not a queue, so it appears only once there is something to
          look up -- and never instead of the load error above it. */}
      {!loadError && !loading && rows.length > 0 && (
        // Same handler the row menu uses, so both routes into the move open
        // the same modal against the same state -- one confirmation, one
        // warning, one request.
        <UserDirectory
          rows={rows}
          onMoveShelter={(row) => { setMoveError(""); setMoving(row); }}
        />
      )}

      {/* Mounted only while open, so the shelter list is fetched fresh each
          time and the fields start empty without a props-to-state effect. */}
      {inviteOpen && (
        <InviteStaffModal
          open
          busy={inviting}
          error={inviteError}
          onClose={() => setInviteOpen(false)}
          onInvite={sendInvite}
        />
      )}

      {moving && (
        <MoveShelterModal
          user={moving}
          busy={movingBusy}
          error={moveError}
          onClose={() => setMoving(null)}
          onMove={moveShelter}
        />
      )}

      <RejectStaffModal
        open={rejecting != null}
        user={rejecting}
        onClose={() => setRejecting(null)}
        onReject={({ reason }) => {
          const u = rejecting;
          setRejecting(null);
          act(u.id, "reject", `${u.fullName}'s request was declined`, { reason });
        }}
      />

      <SuccessModal
        open={notice != null}
        onClose={() => setNotice(null)}
        title={notice?.title ?? ""}
        message={notice?.message ?? "The change has been saved."}
      />
    </AdminLayout>
  );
}
