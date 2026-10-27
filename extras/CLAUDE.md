# CLAUDE.md — Pawhome

Read this first. It is the complete handover for this folder.

## Operating mode: BUILD

This folder is **yours to write in**. Create, edit, refactor, run commands.

**One hard exception:** if the folder `Cha-Ghor` is also connected, it is a
**READ-ONLY reference copy** of a different, finished project. You may read it,
grep it, and copy files *out* of it. You may **never** write to it, run any
mutating git command in it, or create files inside it.

---

## What this project is

**Pawhome** — a new university project, currently a **bare scaffold**.
Nothing domain-specific has been written yet: no migrations, no entities, no
auth wiring, no real pages.

It deliberately mirrors the architecture of **Cha Ghor** (the reference folder):
Spring Boot + React/Vite + a database in Docker, packaged **by feature, not by
layer**. Cha Ghor uses PostgreSQL; **Pawhome uses MySQL 8.4** — do not copy
Postgres-specific SQL across.

| Layer    | Stack                                        |
|----------|----------------------------------------------|
| Backend  | Spring Boot 3.4.1, Java 17, Maven            |
| Frontend | React 19 + Vite + Tailwind **3**             |
| Database | **MySQL 8.4** (InnoDB, utf8mb4), Flyway ON (V1 + V2 applied) |
| Infra    | Docker Compose                               |

---

## Ports — memorise these

Every port is one step above Cha Ghor's so both projects run simultaneously.

| Service      | Pawhome  | Cha Ghor | Note                   |
|--------------|----------|----------|------------------------|
| MySQL        | **3307** | —        | 3306 = any system MySQL |
| Backend      | **8081** | 8080     |                        |
| Vite dev     | **5174** | 5173     |                        |
| Vite preview | **4174** | 4173     |                        |
| AI service   | 8001     | 8000     | not built              |

`docs/PORTS.txt` lists every file each port is set in. Change one, change all
its rows.

---

## Run it

Three tabs. **Database first** — the backend will not boot without it.

```bash
cd docker   && docker compose up -d       # MySQL :3307
cd backend  && ./mvnw spring-boot:run     # :8081
cd frontend && npm install && npm run dev # :5174
```

Verify: `curl http://localhost:8081/api/v1/health` → expect
`"database": "connected"`. Or open http://localhost:5174, which shows the same
check on the scaffold page.

---

## What already exists

```
Pawhome/
├── CLAUDE.md                    this file
├── README.md                    human-facing quickstart
├── .gitignore                   Java + Node + Python + secrets
├── docs/PORTS.txt               where every port is configured
├── docker/
│   ├── docker-compose.yml       MySQL 8.4, name: pawhome
│   └── init/00_extensions.sql   CREATE EXTENSION vector — runs ONCE
├── backend/
│   ├── pom.xml                  web, jpa, validation, security, websocket,
│   │                            lombok, mapstruct, mysql-connector-j,
│   │                            flyway-core + flyway-mysql, jjwt
│   ├── mvnw, mvnw.cmd, .mvn/    Maven wrapper (copied from Cha Ghor)
│   └── src/
│       ├── main/java/com/pawhome/backend/
│       │   ├── PawhomeApplication.java
│       │   ├── config/GlobalExceptionHandler.java
│       │   ├── config/HealthController.java      GET /api/v1/health
│       │   └── security/SecurityConfig.java      ← PERMITS EVERYTHING (scaffold)
│       ├── main/resources/application.yaml
│       ├── main/resources/db/migration/.gitkeep  ← empty, Flyway is OFF
│       └── test/java/.../PawhomeApplicationTests.java
└── frontend/
    ├── vite.config.js           ports pinned 5174 / 4174
    ├── tailwind.config.js       `pw` palette placeholder — REPLACE FROM FIGMA
    ├── postcss.config.js, eslint.config.js, index.html, .env.example
    └── src/
        ├── main.jsx             BrowserRouter (AuthProvider goes inside it)
        ├── App.jsx              scaffold page — DELETE once real pages exist
        ├── index.css            @tailwind directives
        ├── lib/config.js        API_BASE + derived WS_BASE — single source of truth
        ├── lib/apiError.js      readable sentence out of an axios error
        └── api/client.js        axios + JWT header + 401 interceptor
```

### Three scaffold decisions to understand before changing anything

1. **Flyway is disabled** (`spring.flyway.enabled: false` in `application.yaml`).
   With zero migrations it cannot resolve its location. **Turn it on the moment
   `V1__init.sql` exists.**
2. **`SecurityConfig` permits every request.** `spring-boot-starter-security` is
   on the classpath, so without that file every endpoint would sit behind a
   generated console password. BCrypt, CORS and hardening headers are already
   wired. The file carries a numbered TODO block for adding real auth.
3. **`HealthController` is a smoke test**, not a feature. Delete it or keep it;
   it runs `SELECT version()` so "is the DB connected?" has an honest answer.

---

## The user is about to give you two things

### 1. A database idea

Turn it into `backend/src/main/resources/db/migration/V1__init.sql`.

- Naming is `V1__init.sql` — **two underscores**. One underscore and Flyway
  silently ignores the file.
- **MySQL, not Postgres.** No `CREATE TYPE`, no `SERIAL`, no `TIMESTAMPTZ`,
  no `JSONB`, no `::` casts. Use `BIGINT AUTO_INCREMENT`, `DATETIME` (store
  UTC by convention — MySQL has no timezone-aware type), `DECIMAL` for money,
  `ENGINE=InnoDB` for foreign keys and transactions.
- For closed sets use a MySQL `ENUM` column or `VARCHAR` + `CHECK`. Map it in
  Java with `@Enumerated(EnumType.STRING)` and keep the Java constants
  **identical** to the SQL labels, including case.
- CHECK constraints are only enforced from MySQL 8.0.16 — the image is pinned
  to `mysql:8.4` for exactly this reason. Do not downgrade it.
- `BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` for ids.
- `TIMESTAMPTZ`, never `TIMESTAMP`.
- `NUMERIC` for money — never `float`/`double`.
- Put real `UNIQUE` / `CHECK` / `FK` constraints in. The database is the
  guarantee; application checks only exist to produce nice error messages.
- **Ask before inventing tables.** If the idea is vague, ask what the core
  entities and their relationships are rather than guessing a schema the whole
  project then has to live with.

Then: set `spring.flyway.enabled: true`, restart, confirm the
`flyway_schema_history` table appears.

**Never edit a migration that has already run.** Add a new one. In dev you may
reset with `docker compose down -v` (this DELETES all data).

### 2. A Figma design

Work in this order:

1. **Extract the design tokens first.** Pull the palette, font family, spacing
   scale and radii out of Figma and replace the placeholder `pw` colours in
   `frontend/tailwind.config.js`. Everything after this inherits them, so doing
   it first avoids a repaint later.
2. **Identify the layouts**, not just the screens. Cha Ghor has one layout
   component per role (`AdminLayout`, `SupervisorLayout`, `WorkerLayout`), each
   with its own sidebar. Decide Pawhome's equivalent before building pages.
3. **Build shared components before pages** — button, card, table, modal,
   form field. Pages that each invent their own button are how a design drifts.
4. **Then pages**, one per Figma frame, under
   `frontend/src/pages/<role>/<Page>.jsx`.
5. Match spacing and colour to the design, but **do not** pixel-copy a layout
   that will not survive a narrow viewport. Use flex/grid; make tables scroll
   inside their own container.

If the Figma arrives as screenshots, read them directly. If it is a link you
cannot open, ask for exported images.

---

## Build order once you have both

Do not start feature work before step 3 passes.

1. `V1__init.sql` → enable Flyway → confirm the tables exist.
2. Port authentication from Cha Ghor (see the next section) → confirm login
   returns a JWT and a protected endpoint 401s without it.
3. Confirm the health check is green end to end.
4. First feature module, complete: entity → repository → service → controller →
   dto → one React page. Get this one **right**; it becomes the template every
   later module is copied from.
5. Remaining modules, following that template.
6. Delete `HealthController` and the scaffold `App.jsx` home page.

---

## What to copy from Cha-Ghor, and what not to

Paths are relative to
`Cha-Ghor/chaghor/backend/src/main/java/com/chaghor/chaghor/`.

### Copy nearly verbatim — this is the real value

| File | Why |
|---|---|
| `security/JwtService.java` | HS384 signing + a boot-guard that refuses to start on the dev key outside dev |
| `security/JwtAuthFilter.java` | token → `Authentication`; re-loads the user each request, so disabling an account takes effect immediately |
| `security/LoginRateLimitFilter.java` | 5 attempts / 60s, keyed on the **TCP socket** not `X-Forwarded-For` |
| `security/AppUserDetails.java` | roles + the two-flag `isEnabled()` (active **and** approved) |
| `security/CustomUserDetailsService.java` | username → UserDetails |
| `auth/AuthController.java` | especially the `DisabledException` block — it re-verifies the password before explaining *why* login failed, so the error is not a username-enumeration oracle |
| `user/User.java`, `Role.java`, `ApprovalStatus.java` | the user model the above depends on |
| `audit/AuditService.java` | append-only trail, actor read from the security context |

**After copying:** change every `package com.chaghor.chaghor.X;` to
`package com.pawhome.backend.X;` and fix imports. **Use an IDE refactor, not
`sed`** — sed corrupts string literals.

**Then check these two, they are easy to miss:**
- `JwtService.DEV_SECRET` is a hardcoded constant compared against the
  `app.jwt.secret` default in `application.yaml`. They must be **identical**.
  Pawhome's yaml already has its own value — update the constant to match.
- `SecurityConfig`'s `permitAll` list — delete routes Pawhome does not have
  (`/ws/**`, `/api/v1/supply/track/**`, `/api/v1/auth/login/pin`).

### Do NOT copy

- **`V1`–`V38` migrations.** They are 38 incremental patches to a tea estate,
  half of them fixes to earlier ones. Start clean at `V1`.
- Any of the 25 domain modules (`payroll/`, `leaf/`, `worker/`, …).
- `ai_service/` unless Pawhome actually needs AI.
- The PWA (`sw.js`, `outbox.js`, `uuid.js`, `useOnlineStatus.js`) unless
  Pawhome genuinely works offline in the field. It is real complexity and a
  service worker needs HTTPS anywhere except `localhost`.

---

## Conventions — follow these without being asked

**Package by feature, never by layer.** No `controllers/` `services/`
`repositories/` folders. Instead:

```
com.pawhome.backend.pet/
  Pet.java              @Entity          one table row
  PetRepository.java    interface        Spring Data writes the SQL
  PetService.java       @Service         the rules; owns @Transactional
  PetController.java    @RestController  URLs + @PreAuthorize; delegates only
  dto/                  records          what crosses the wire
```

The controller holds **no logic** — every method is one line delegating to the
service. Entities never leave the backend; DTOs do.

**Other rules:**

- `${ENV_VAR:default}` for every config value. A fresh clone must run with zero
  setup, and any environment must override without editing files.
- `ddl-auto: validate`, **never** `update`. Flyway owns the schema.
- Constructor injection. `@RequiredArgsConstructor` over `final` fields, or an
  explicit constructor when the class also needs `@Value`.
- `BigDecimal` for money. Never `double`. Always an explicit scale +
  `RoundingMode`.
- Foreign keys as plain `Long` columns, resolved in-memory in the service —
  do not introduce `@ManyToOne`. (Cha Ghor's convention; keep it consistent.)
- Errors are `ResponseStatusException` with a sentence a real user can read.
  `GlobalExceptionHandler` already turns them into clean JSON.
- Cross-module calls go **service → service**, never controller → controller.
- Best-effort side effects (sockets, SMS, audit) go **last** and are wrapped in
  try/catch — they must never fail a write that already committed.
- **Comment the *why*, especially the bug.** Not "validates the input" but
  "this used to read X, which meant Y broke." This is the single thing that
  makes the code defensible in a viva, and it cannot be retrofitted.

---

## Traps — Cha Ghor hit these; do not repeat them

| Trap | What happens | Do this instead |
|---|---|---|
| `setAllowedOrigins` for CORS | wildcards silently never match; API blocked while WebSockets work | use `setAllowedOriginPatterns` in **both** `SecurityConfig` and `WebSocketConfig` |
| Vite has no `--host` | phone gets connection-refused on the LAN | ports are already pinned; run `npm run dev -- --host` for phone testing |
| One base wage in two places | payslip and daily ledger disagree | any value used by two calculations gets **one** source; never duplicate a formula input |
| Stale comments | docs contradict code — an examiner will find it | update the comment in the same commit as the code |
| One test file | 285 Java files, one generated stub | **write unit tests for pure calculation logic as you go** |
| Rate limiter reading `X-Forwarded-For` | protection reads as working, does nothing | trust `getRemoteAddr()` only, unless a proxy is explicitly allow-listed |
| Service worker `staleWhileRevalidate` on JS | old logic runs, needs two reloads | network-first for code; only cache what cannot break behaviour |

Also already handled here, do not undo:
- `docker-compose.yml` has `name: pawhome`. Without it Compose names the project
  after its folder (`docker`) — identical to Cha Ghor's — and the two projects
  stop each other's containers.
- The volume is `pawhome_pgdata`. Sharing `chaghor_pgdata` means both projects
  mount the same data directory.

---

## Debugging

**A giant Spring startup stack trace: read it BOTTOM-UP.** The last
`Caused by:` is the real cause; everything above is the failure propagating. A
`UnsatisfiedDependencyException` on `jwtAuthFilter` almost always means
MySQL is down.

| Symptom | Cause |
|---|---|
| `Communications link failure` / `Connection refused :3307` | Docker not running, or MySQL still starting (it takes ~30s on first boot) → `open -a Docker`, `docker compose up -d`, then `docker compose logs pawhome-db` |
| `Schema-validation: missing table [x]` | migration did not run, or the entity does not match it |
| Flyway `checksum mismatch` | you edited a migration that already ran. Add a new `V3__` instead. Dev-only reset: `docker compose down -v` (deletes data) |
| `port is already allocated` | `lsof -i :3307` |
| Lombok getters "not found" in IntelliJ | Settings → Build → Compiler → Annotation Processors → Enable |
| Tailwind classes do nothing | check `content` globs, that `index.css` has the `@tailwind` lines, and that `main.jsx` imports it |
| Both projects fighting over containers | a missing `name:` in a compose file |

---

## Answering style

Cite file paths when you reference code. Quote the specific lines that matter
rather than pasting whole files. When a question spans modules, trace the actual
call path. Say "I don't see that in the code" when you don't — never infer
behaviour that isn't written down.

Before writing a schema or a screen from a vague description, **ask**. A wrong
guess at the data model is the most expensive mistake available here.
