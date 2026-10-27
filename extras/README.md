# Pawhome

Scaffold only. No domain modules, no migrations, no auth wired yet.

Built on the same structure as Cha Ghor: Spring Boot + React/Vite + a database
in Docker, packaged **by feature, not by layer**. Cha Ghor uses PostgreSQL;
Pawhome uses **MySQL 8.4**.

---

## Ports

Every port is one step above Cha Ghor's, so both projects run side by side.

| Service    | Pawhome | Cha Ghor | Note                        |
|------------|---------|----------|-----------------------------|
| MySQL      | **3307**| —        | 3306 = any system MySQL     |
| Backend    | **8081**| 8080     |                             |
| Vite dev   | **5174**| 5173     |                             |
| Vite preview | **4174** | 4173  |                             |
| AI service | 8001    | 8000     | not built yet               |

---

## Run it

Three terminal tabs. **The database must be first** — the backend will not boot
without it.

```bash
# 1. Database
cd docker && docker compose up -d
docker ps                                   # pawhome-mysql should be listed

# 2. Backend                                → http://localhost:8081
cd backend && ./mvnw spring-boot:run

# 3. Frontend                               → http://localhost:5174
cd frontend && npm install                  # first time only
npm run dev
```

Verify the whole chain:

```bash
curl http://localhost:8081/api/v1/health
```

Expect `"database": "connected"`. Or just open http://localhost:5174 — the
scaffold page shows the same check.

---

## Layout

```
Pawhome/
├── docker/
│   ├── docker-compose.yml          MySQL 8.4 on 3307
│   └── init/00_placeholder.sql     runs ONCE, on first boot only
├── backend/
│   ├── pom.xml                     Spring Boot 3.4.1, Java 17
│   ├── mvnw, .mvn/                 Maven wrapper (no local Maven needed)
│   └── src/main/
│       ├── java/com/pawhome/backend/
│       │   ├── PawhomeApplication.java
│       │   ├── config/  GlobalExceptionHandler, HealthController
│       │   └── security/SecurityConfig.java      ← currently permits ALL
│       └── resources/
│           ├── application.yaml
│           └── db/migration/       ← empty; Flyway is OFF until V1 exists
└── frontend/
    ├── vite.config.js              ports pinned to 5174 / 4174
    ├── tailwind.config.js          Tailwind 3 — do not upgrade to 4
    └── src/
        ├── lib/config.js           single source of truth for the API host
        ├── lib/apiError.js         readable message out of an axios error
        └── api/client.js           axios + JWT header + 401 interceptor
```

---

## Next steps, in order

1. **`V1__init.sql`** in `backend/src/main/resources/db/migration/`
   (two underscores after the version, or Flyway silently ignores it).
2. Set `spring.flyway.enabled: true` in `application.yaml`.
3. Port the auth module from Cha Ghor — see the TODO block at the top of
   `SecurityConfig.java` for the exact file list and the six steps.
4. Add your first feature package: `com.pawhome.backend.<thing>` containing
   `Thing`, `ThingRepository`, `ThingService`, `ThingController`, `dto/`.

---

## Conventions carried over from Cha Ghor

- **Package by feature.** Never `controllers/` `services/` `repositories/`.
- **Five shapes per module:** Entity, Repository (interface), Service (rules +
  `@Transactional`), Controller (URLs + `@PreAuthorize`, delegates only), DTO.
- **`${ENV_VAR:default}` for every config value** — a fresh clone runs with no
  setup; any environment overrides without editing files.
- **`ddl-auto: validate`, never `update`.** Flyway owns the schema.
- **DB constraints are the guarantee**, app checks are for good error messages.
  Do both, and catch `DataIntegrityViolationException` for the race between.
- **`BigDecimal` for money.** Never `double`. Explicit scale + RoundingMode.
- **Comment the *why*, especially the bug.** Not "validates input" but "this
  used to read X, which broke Y." It cannot be retrofitted later.
- **Write tests.** Cha Ghor has one test file across 285 Java files. Do better.

---

## Gotchas already handled here

- `docker-compose.yml` sets `name: pawhome`. Without it Compose names the
  project after the folder (`docker`) — same as Cha Ghor's — and the two
  projects stop each other's containers.
- The volume is `pawhome_pgdata`, not `chaghor_pgdata`. Sharing one means both
  projects mount the same data directory.
- `SecurityConfig` uses `setAllowedOrigins`, which is **exact match** — CORS
  wildcards like `http://192.168.*.*:5174` do not work. Use the exact IP when
  demoing on a phone. (Cha Ghor has this bug; it is documented there, not
  copied here.)
