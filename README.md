
# PawHome

A pet adoption and boarding management system — built so the database, not the
application code, holds the rules.

CSE 3522 · Database Management Systems Laboratory
United International University

## What it does

PawHome manages the whole life of an animal in a shelter's care, not just the
adoption. A pet is registered, listed publicly, applied for and adopted — and
then, as an owned pet, comes back for boarding or a daycare subscription, each
with its own rooms, capacity limits, daily care record and bill.

Three roles use it: adopter, shelter staff, administrator.

## Stack

| Layer    | Technology                                    |
|----------|-----------------------------------------------|
| Backend  | Spring Boot 3.4, Java 17, Maven wrapper       |
| Database | MySQL 8.4 in Docker, schema managed by Flyway |
| Frontend | React 19, Vite, Tailwind CSS 3                |
| Auth     | Spring Security + JWT                         |

## The database

32 base tables · 4 views · 69 foreign keys · 39 CHECK constraints · 124 indexes

Diagrams and the full query listing live in `extras/docs/`:

- `pawhome-core-erd.png` — the 17 core tables
- `pawhome-full-erd.png` — all 32 tables and all 69 relationships
- `pawhome-schema-diagram.png` — every column with its type
- `pawhome-diagrams.drawio` — editable source

The SQL is grouped in `backend/Queries/`: all queries, the views, the
transactions and the indexes.

## Running it

**1. Database**

```
docker compose -f docker/docker-compose.yml up -d
```

MySQL listens on host port **3307**. Flyway applies all 19 migrations on the
first backend start.

**2. Backend**

```
cd backend
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Runs on **http://localhost:8081**.

Email sending needs `backend/src/main/resources/application-secret.yaml`, which
is deliberately not committed. Everything else works without it.

**3. Frontend**

```
cd frontend
npm install
npm run dev
```

Runs on **http://localhost:5174**.

## Layout

```
backend/    Spring Boot API, Flyway migrations, Queries/
frontend/   React + Vite client
docker/     MySQL compose file
extras/     report, diagrams, presentation, design notes
```

## Team

- Mir Mukararam Hossain
- Nowshin Islam Tonni
- ⁠Faiyaj Jarif