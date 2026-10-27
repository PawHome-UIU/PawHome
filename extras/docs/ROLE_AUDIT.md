# PawHome — role completeness audit

*Method: mechanical cross-reference of all 151 backend endpoints against all 192
frontend API calls, plus a schema-driven walk of each role's journey. Every
finding below cites a file and line that was actually read.*

---

## Verdict

| | Verdict |
|---|---|
| **Adopter** | Complete. Every endpoint reachable, every journey closed. Two cosmetic gaps. |
| **Staff** | Complete. Three unused endpoints, one of them my doing. |
| **Admin** | **Incomplete.** The dashboard is still a mock, and three finished feature areas have no screen. |
| **Security** | Clean. No role can reach another's endpoints. |

**What is structurally sound**, and worth saying before the fault list:

- **0 broken calls.** No button in the app hits an endpoint that does not exist.
- **0 role mismatches.** No page calls an endpoint its portal's role is denied.
- **0 gate mismatches.** Every `@PreAuthorize` agrees with the `SecurityConfig`
  filter chain — the class of bug that made the staff slip controllers dead code
  earlier has not recurred.
- **13 ungated endpoints, and they are exactly the intended public surface**:
  the eight auth flows, `/files`, `/health`, `/pets`, `/pets/{id}`, `/shelters`.
  Nothing private is exposed.
- **0 orphaned pages.** All 46 routes are reachable; the six that look unlinked
  (`/verify-email`, `/reset-password`, `/accept-invite`, and the three
  `/settings`) arrive by email link or from the avatar menu's computed URL.

---

## Findings, most severe first

### 1. The admin dashboard is still the design mock — ADMIN

The first screen an administrator sees is fabricated.

- Both charts render hardcoded arrays: `AdminDashboard.jsx:15` imports
  `ADOPTIONS_BY_MONTH`, `APPLICATIONS_BY_STATUS`, `ACTIVITY` from
  `frontend/src/data/admin.js`, whose own first line says *"Placeholder
  analytics **until the reporting endpoints exist**."* They exist:
  `analytics/AdminAnalyticsController.java:39-40`.
- The only live call on the page is `api.get("/admin/shelters")`
  (`AdminDashboard.jsx:33`).
- The staff approve/reject controls at `AdminDashboard.jsx:165-172` are
  `<button>` elements **with no `onClick`**, hardcoded to *"Maria G."* — a name
  that does not come from the database. The working endpoints exist and are
  called from `UsersPage`.

This is the finding most likely to be noticed in a demo, because it is the
landing screen and the fake name is visible.

### 2. Admin has no Billing screen — ADMIN

Three ADMIN-only endpoints are finished and unreachable:
`GET /admin/slips`, `/admin/slips/totals`, `/admin/slips/{id}`
(`billing/AdminSlipController.java:41,46,51`). There is no nav entry
(`AdminLayout.jsx:4-10`), no route (`App.jsx:105-111`), and no call anywhere in
`frontend/src`.

Consequence: **the network operator cannot see what is being billed.** Staff can
see their own shelter's takings; the admin who owns the network cannot see any.
`/staff/slips` is denied to admins by the filter chain, so there is no
workaround.

### 3. Admin has no Daycare screen — ADMIN

`GET /admin/daycare/subscriptions` (`daycare/AdminDaycareController.java:42`) has
no route, nav entry or caller.

Note: the same class at `:20-25` *explicitly argues* for omitting a network-wide
occupancy endpoint. That omission is deliberate and correct. The subscriptions
list is not covered by it.

### 4. `audit_log` has no writer — ADMIN

The table is declared at `V1__init.sql:685` and its comment at `:683` says it
*"Powers 'Recent Activity' on the admin dashboard."* **Nothing writes to it.**
There is no `audit` package (verified: 20 packages, none named audit), and the
only mention of the table in Java is a passing remark in `BillingSlip.java:38`.
The Recent Activity panel renders the hardcoded `ACTIVITY` array instead.

So the audit trail the schema promises does not exist. For a database course
project this is worth either building or deleting — a table nothing writes is a
claim the schema makes and the code does not keep.

### 5. `v_pet_listing` is a dead view — all roles

Defined at `V1__init.sql:755`, redefined at `V9__household_pets.sql:108`, and its
header says it is *"consumed by FOUR pages"*. **It is queried by nothing.** All
eight references in the codebase are comments; the listing projection is instead
hand-written three times (`PetService.java:893`, `:942`, `:1019`).

The sibling view `v_adopter_traits` **is** live — queried at
`PetService.java:234`, `:250` and `QuizTakingService.java:117` — so this is not a
"views don't work" problem, just one that drifted out of use. The risk is exactly
the drift the view was created to prevent.

### 6. Admin cannot reorder quiz questions — ADMIN

`POST /admin/quiz/questions/reorder` (`AdminQuizController.java:69`) is
implemented (`QuizService.java:217`) with no caller. `display_order` is set once
at creation as `max + 1` (`QuizService.java:138`) and can never be changed.

### 7. Admin cannot manage a shelter's rooms — ADMIN

Three endpoints unused: `POST /admin/shelters/{id}/suites`,
`PUT .../suites/{id}`, `POST .../suites/{id}/deactivate`. Staff manage rooms on
My Shelter, so nothing is *blocked* — but an admin looking at a shelter's suites
tab can only read them.

### 8. Staff daycare attendance history is unreachable — STAFF *(partly my doing)*

- `GET /staff/daycare/attendance` — the date-ranged attendance board. Never
  wired. Staff can see who is in **today** but cannot review any previous day.
- `GET /staff/daycare/occupancy` — **went dead when I added `/staff/daycare/today`
  last session**, which returns the band tiles as part of a larger payload. That
  was deliberate and documented, but it leaves the older endpoint orphaned. It
  should either be deleted or the duplication acknowledged.

### 9. The owner is told where their pet is and never shown it — ADOPTER

`SubscriptionRow` carries `hereToday`, `todayVisitId`, `todaySuiteName`
(`DaycareDtos.java:65-69`) and is returned to the owner by
`OwnerDaycareController.java:52`. Only the staff page reads them
(`DaycarePage.jsx:384-386`); `AdopterDaycarePage.jsx` uses only `slipsIssued`.

The data is already on the wire. "Mittens is at the shelter now, in Cat Loft A"
is a one-line render away.

### 10. No adopter-visible care record — ADOPTER *(medium confidence)*

`care_tasks` and `care_logs` (`V1__init.sql:575`, `:611`) are staff-only — the
sole controller is `care/StaffCareController.java:26`. An owner whose pet is
boarding sees `boarding_updates` and their own instructions, but not the actual
feeding and medication log.

Lower confidence than the rest: no comment justifies *or* promises owner
visibility, so this may be an undocumented scope decision rather than a gap.

### 11. Minor unused endpoints

`GET /staff/pets/{id}/vaccinations` (staff use the shelter-wide
`/staff/vaccinations`), `GET /admin/shelters/{id}`, `GET /admin/users/counts`.
All harmless; each is a second way to get data a screen already has.

---

## Deliberate omissions — checked, and *not* gaps

This codebase comments its scope decisions, and the audit respected them:

- **Adopter withdraw / WITHDRAWN status** — fully wired end to end.
- **`DeclineReason.PET_ALREADY_MATCHED`** — omitted from the UI on purpose
  (`DeclineApplicationModal.jsx:11`); written server-side instead.
- **PetStatus ADOPTED / BOARDING / OWNED** — not user-settable by design
  (`PetStatus.java:6-21`); set by the flows that cause them.
- **All six BoardingStatus values** reachable.
- **Notification preferences** — wired *and actually honoured* at send time
  (`NotificationService.java:117-133`, `HealthReminderJob.java:99-100`).
- **All three quiz types** takeable; DRAFT/ACTIVE/ARCHIVED all settable.
- **No hand-issued billing slip** — argued at `AdminSlipController.java:27-30`.
- **`pets.view_count` not surfaced** — deliberate (`PetDetailPage.jsx:23`).
- **Network-wide daycare occupancy** — deliberate
  (`AdminDaycareController.java:20-25`).

---

## What was not verified

The app was not exercised at runtime. "No UI" throughout means *no code path in
`frontend/src` references the endpoint or field* — conclusive for findings 2, 3,
6, 7, 9, weaker as a claim about intent for finding 10.

The checkers themselves were control-tested: deliberately stripping a
`@PreAuthorize` off `GET /shelters/mine/rooms` made it correctly report as
ungated, and the parser was fixed mid-audit after it produced a **false security
finding** — it only looked for gates *before* the mapping annotation, and
`ShelterController` puts them after.

---

## Suggested order of work

1. Replace the admin dashboard's mock data and wire its dead buttons *(finding 1)*
2. Admin Billing screen — three endpoints already waiting *(2)*
3. Decide `audit_log`: build the writer, or drop the table *(4)*
4. Admin Daycare screen *(3)*
5. Show the owner where their daycare pet is *(9)* — smallest change on the list
6. Either use `v_pet_listing` or drop it *(5)*
7. Quiz reorder, attendance history, admin suite management *(6, 8, 7)*
