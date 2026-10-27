-- Runs automatically the FIRST time the container starts (empty volume).
-- Editing this file later does nothing until `docker compose down -v`,
-- which DELETES all data.
--
-- Nothing is needed here. MySQL has no extensions (this file used to run
-- CREATE EXTENSION vector for Postgres + pgvector), and Flyway owns the whole
-- schema -- see backend/src/main/resources/db/migration/.
SELECT 'pawhome: container init complete' AS note;
