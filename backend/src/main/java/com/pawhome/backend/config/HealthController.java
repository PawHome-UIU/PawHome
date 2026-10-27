package com.pawhome.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

// Proves the whole chain works on day one: HTTP -> Spring -> JDBC -> Postgres.
//
//   curl http://localhost:8081/api/v1/health
//
// Delete this once you have real endpoints, or keep it as a smoke test.
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", "pawhome-backend");
        out.put("status", "ok");
        try {
            // SELECT 1, not SELECT version(). This endpoint is PUBLIC, and the
            // MySQL version string is free reconnaissance -- it tells an
            // attacker exactly which published CVEs to try.
            jdbc.queryForObject("SELECT 1", Integer.class);
            out.put("database", "connected");
        } catch (Exception e) {
            // Named, not swallowed: a backend that cannot reach its database
            // should say so rather than reporting a cheerful "ok".
            //
            // The exception MESSAGE is logged, never returned: a JDBC failure
            // spells out the host, port, database name and username in plain
            // text, and this response goes to anyone who asks.
            log.error("Health check could not reach the database", e);
            out.put("database", "UNREACHABLE");
        }
        return out;
    }
}
