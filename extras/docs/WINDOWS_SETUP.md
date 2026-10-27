# PawHome — setting up on Windows 11, from the terminal

Everything here is done with **winget**, which ships with Windows 11. No
websites, no installers to click through.

Open **PowerShell** (press Start, type `powershell`, Enter). You do not need to
run it as administrator — winget will ask for elevation itself when it needs it.

---

## 1. Java 17 — the one that actually matters

The project targets **Java 17** and will not build on anything older. That is
not a preference; it is `<java.version>17</java.version>` in
`backend/pom.xml`, and Spring Boot 3.4.1 requires 17 as a minimum.

```powershell
winget install --exact --id EclipseAdoptium.Temurin.17.JDK
```

`--exact` matters. Without it, winget matches on name as well as ID and a
search for "Temurin 17" can offer you the JRE (which cannot compile) or a
different major version.

**Then close PowerShell and open a new one.** Windows reads `PATH` when a
terminal starts, so the window you ran the install in still cannot see Java.
This is the single most common reason people think the install failed.

Check it:

```powershell
java -version
```

You want to see `17.x.x`:

```
openjdk version "17.0.13" 2024-10-15
OpenJDK Runtime Environment Temurin-17.0.13+11 (build 17.0.13+11)
```

---

## 2. If `java -version` shows the wrong version

This happens when another JDK is already installed — very common if you have
Android Studio or an older Java.

See everything winget knows about:

```powershell
winget list --name Java
winget list --name Temurin
```

Java 17 must be **first** on `PATH`, and `JAVA_HOME` must point at it, because
Maven reads `JAVA_HOME` and ignores `PATH` entirely. Set it permanently
(adjust the version folder to match what is actually there):

```powershell
[Environment]::SetEnvironmentVariable(
  "JAVA_HOME",
  "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot",
  "User")
```

Find the exact folder name first if you are not sure:

```powershell
Get-ChildItem "C:\Program Files\Eclipse Adoptium"
```

Open a new terminal, then confirm **both**:

```powershell
$env:JAVA_HOME
java -version
```

If `JAVA_HOME` prints nothing, the variable did not take — open a new terminal
and try again, and make sure you did not typo the path.

---

## 3. The rest of what the project needs

```powershell
winget install --exact --id Git.Git
winget install --exact --id OpenJS.NodeJS.LTS
winget install --exact --id Docker.DockerDesktop
```

**You do NOT need to install Maven.** The repository carries the Maven wrapper
(`backend/mvnw.cmd`), which downloads the right Maven version by itself. Use
the wrapper, not a system `mvn` — that is the point of it: everyone builds with
the same Maven whether or not they installed one.

Docker Desktop needs a reboot after installing, and must be **running** (whale
icon in the system tray) before the database will start.

Verify the rest, in a new terminal:

```powershell
git --version
node --version
docker --version
```

---

## 4. Run the project

```powershell
git clone <the repo url>
cd Pawhome
```

**Database first.** The backend will not start without it — Flyway connects on
boot to check the migrations.

```powershell
cd docker
docker compose up -d
docker ps
```

`docker ps` should show `pawhome-mysql` as `healthy`. It takes 30–60 seconds on
the very first run while MySQL initialises. If it says `starting`, wait and run
`docker ps` again.

**Backend:**

```powershell
cd ..\backend
.\mvnw.cmd spring-boot:run
```

Note the `.\` — PowerShell will not run a script in the current directory
without it, and `mvnw` alone gives "not recognized". On Windows it is
`mvnw.cmd`; `./mvnw` is the Mac/Linux spelling and will not work here.

The first run downloads Maven and every dependency. Expect several minutes and
a lot of output. It is finished when you see Tomcat started on port 8081.

PawHome deliberately uses 8081, not the Spring default 8080, so it can run
alongside another Spring project on the same machine. See docs/PORTS.txt.

**Frontend**, in a second terminal:

```powershell
cd frontend
npm install
npm run dev
```

Then open the URL Vite prints — http://localhost:5174 (again one above the
Vite default, for the same reason).

---

## 5. The secret file, which is not in git

`backend/src/main/resources/application-secret.yaml` holds the mail password
and is deliberately excluded by `.gitignore`, so a fresh clone does not have
it. The app starts without it; only outgoing email fails. **Ask Faiyaj for it
directly — do not paste it into a chat, an issue, or a commit.**

---

## Quick reference

| What | Command |
|---|---|
| Install Java 17 | `winget install --exact --id EclipseAdoptium.Temurin.17.JDK` |
| Check Java | `java -version` (in a **new** terminal) |
| Check JAVA_HOME | `$env:JAVA_HOME` |
| Start the database | `cd docker` then `docker compose up -d` |
| Stop the database | `docker compose down` |
| Reset the database completely | `docker compose down -v` (deletes all data) |
| Run the backend | `cd backend` then `.\mvnw.cmd spring-boot:run` |
| Run the frontend | `cd frontend` then `npm run dev` |
| Connect to MySQL | `docker exec -it pawhome-mysql mysql -u pawhome -ppawhome_dev_pw pawhome` |

MySQL is on **port 3307**, not 3306 — deliberately, so it cannot collide with a
MySQL already installed on the machine. See `docs/PORTS.txt`.

---

## When something goes wrong

**`java` is not recognized** — you did not open a new terminal after
installing. Close it and open a new one.

**`mvnw` is not recognized** — you need `.\mvnw.cmd`, with the leading `.\`.

**Backend fails with a connection refused on 3307** — Docker Desktop is not
running, or the container has not finished starting. Check `docker ps`.

**Backend fails with a Flyway validation error** — the database has an older
schema than the code expects. Fix it by resetting: `cd docker`,
`docker compose down -v`, `docker compose up -d`. This deletes all local data
and rebuilds the schema from the migrations, which is exactly what you want on
a dev machine.

**A build error mentioning class file version 61 or 65** — the wrong JDK is
being used. Version 61 is Java 17, 65 is Java 21. Go back to section 2 and
check `JAVA_HOME`.
