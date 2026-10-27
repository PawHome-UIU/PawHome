package com.pawhome.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttles /auth/login and /auth/register.
 *
 * WITHOUT THIS, THE LOGIN ENDPOINT IS AN OFFLINE PASSWORD CRACKER WITH A REST
 * INTERFACE. BCrypt at cost 10 takes ~100ms, so a single attacker thread gets
 * ~10 guesses a second and ten threads get 100 -- easily enough to walk a
 * common-password list against every account in the database overnight.
 *
 * TWO LIMITS, AND THE SECOND ONE IS THE SUBTLE PART
 * -------------------------------------------------
 *   per IP     broad. Stops one machine grinding through many accounts.
 *   per EMAIL  targeted. Stops a botnet spreading one account's guesses across
 *              many IPs, which the per-IP limit alone would never see.
 *
 * The per-email limit deliberately does NOT lock the account. Locking is itself
 * an attack: anyone who knows your email could keep you permanently signed out
 * by failing on purpose. So it delays, and it expires on its own -- an
 * inconvenience to a real user having a bad morning, and a wall to a script.
 *
 * A SUCCESSFUL LOGIN CLEARS BOTH COUNTERS. Only failures accumulate, so someone
 * who signs in correctly ten times in a row is never throttled.
 *
 * IN MEMORY, AND THAT IS A REAL LIMIT. One process, one map: restart the app
 * and every counter resets, and a second instance behind a load balancer would
 * have its own. Fine for one dockerised instance; a real deployment needs Redis
 * or the limit is per-instance and an attacker just reconnects until they land
 * on a fresh one.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    /** Failures from one IP before it waits. Generous: offices share an IP. */
    private static final int MAX_PER_IP = 20;

    /** Failures against one email before it waits. Tighter -- it is targeted. */
    private static final int MAX_PER_EMAIL = 5;

    /** New accounts from one IP per window. Registration is not a hot path. */
    private static final int MAX_REGISTRATIONS_PER_IP = 5;

    /** Emails one address may cause to be sent per window. */
    private static final int MAX_MAILERS_PER_IP = 5;

    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** Stop the maps growing without bound if nobody ever sweeps them. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Window> counters = new ConcurrentHashMap<>();
    private volatile Instant lastSweep = Instant.now();

    private static final class Window {
        final AtomicInteger hits = new AtomicInteger();
        volatile Instant resetAt;
        Window(Instant resetAt) { this.resetAt = resetAt; }
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isLogin = "POST".equals(request.getMethod()) && path.endsWith("/auth/login");
        boolean isRegister = "POST".equals(request.getMethod()) && path.endsWith("/auth/register");

        // Each of these SENDS AN EMAIL from the project's Gmail account. Left
        // open, they are a way to have that account mail a stranger repeatedly
        // -- which gets the address rate-limited by Google, then blocked.
        boolean isMailer = "POST".equals(request.getMethod())
                && (path.endsWith("/auth/forgot-password")
                 || path.endsWith("/auth/resend-verification"));

        if (isMailer) {
            String key = "mail:" + clientIp(request);
            if (exceeded(key, MAX_MAILERS_PER_IP)) {
                reject(response, "Too many requests. Please wait a few minutes.");
                return;
            }
            record(key);
            chain.doFilter(request, response);
            return;
        }

        if (!isLogin && !isRegister) {
            chain.doFilter(request, response);
            return;
        }

        sweepIfDue();

        String ip = clientIp(request);

        if (isRegister) {
            if (exceeded("reg:" + ip, MAX_REGISTRATIONS_PER_IP)) {
                reject(response, "Too many accounts created from here. Try again later.");
                return;
            }
            chain.doFilter(request, response);
            // Only a created account counts. A rejected one (bad email, duplicate)
            // should not use up someone's allowance for getting the form wrong.
            if (response.getStatus() == 201) record("reg:" + ip);
            return;
        }

        // --- login ---------------------------------------------------------
        // The email is read from a query parameter if present, but the real one
        // is in the JSON body, which cannot be read here without consuming the
        // stream the controller needs. So the per-email counter is keyed after
        // the fact, in AuthService, via noteFailure(). This filter owns the
        // per-IP half and the enforcement of both.
        String ipKey = "login:ip:" + ip;

        if (exceeded(ipKey, MAX_PER_IP)) {
            log.warn("Rate limit: too many failed logins from {}", ip);
            reject(response, "Too many failed sign-in attempts. Please wait a few minutes.");
            return;
        }

        chain.doFilter(request, response);

        int status = response.getStatus();
        if (status == 200) {
            // Success wipes the slate: a real person who mistyped twice and then
            // got it right should not be closer to a limit than someone who
            // never failed at all.
            counters.remove(ipKey);
        } else if (status == 401) {
            // 403 is deliberately NOT counted. That is "correct password, account
            // not approved" -- the person already proved the password, so they
            // are not guessing.
            record(ipKey);
        }
    }

    /** Called by AuthService once the email on a failed attempt is known. */
    public boolean emailIsThrottled(String email) {
        return exceeded("login:email:" + normalise(email), MAX_PER_EMAIL);
    }

    public void noteEmailFailure(String email) {
        record("login:email:" + normalise(email));
    }

    public void clearEmail(String email) {
        counters.remove("login:email:" + normalise(email));
    }

    // ---------------------------------------------------------------- internals

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean exceeded(String key, int max) {
        Window w = counters.get(key);
        if (w == null) return false;
        if (Instant.now().isAfter(w.resetAt)) {
            counters.remove(key);
            return false;
        }
        return w.hits.get() >= max;
    }

    private void record(String key) {
        if (counters.size() >= MAX_TRACKED_KEYS) sweep();
        counters.compute(key, (k, w) -> {
            Instant now = Instant.now();
            if (w == null || now.isAfter(w.resetAt)) {
                Window fresh = new Window(now.plus(WINDOW));
                fresh.hits.set(1);
                return fresh;
            }
            w.hits.incrementAndGet();
            return w;
        });
    }

    private void sweepIfDue() {
        if (Duration.between(lastSweep, Instant.now()).compareTo(WINDOW) > 0) sweep();
    }

    private void sweep() {
        Instant now = Instant.now();
        counters.entrySet().removeIf(e -> now.isAfter(e.getValue().resetAt));
        lastSweep = now;
    }

    /**
     * The caller's address.
     *
     * X-Forwarded-For IS DELIBERATELY IGNORED. Any client can send that header,
     * so trusting it would let an attacker put a different fake value on every
     * request and never hit the limit at all -- a rate limiter that is trivially
     * bypassed is worse than none, because it looks like protection.
     *
     * If this ever runs behind a real reverse proxy, the fix is to configure
     * Spring's ForwardedHeaderFilter with the trusted proxy list, so the header
     * is honoured only when it comes FROM that proxy. Reading it here by hand
     * is how the bypass gets shipped.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
