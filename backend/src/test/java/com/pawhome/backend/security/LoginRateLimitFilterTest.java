package com.pawhome.backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter, which is only useful if it cannot be walked around.
 *
 * Each test names the bypass it is closing, because a limiter that looks like
 * protection and is not is worse than having none.
 */
class LoginRateLimitFilterTest {

    private final LoginRateLimitFilter filter = new LoginRateLimitFilter();

    /** One failed login attempt from an address. */
    private int failedLoginFrom(String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(401);
        filter.doFilter(req, res, chain);
        return res.getStatus();
    }

    private int successfulLoginFrom(String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
        filter.doFilter(req, res, chain);
        return res.getStatus();
    }

    @Test
    @DisplayName("a burst of failures from one address is eventually refused with 429")
    void ipIsThrottled() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(failedLoginFrom("10.0.0.1")).isEqualTo(401);
        }
        assertThat(failedLoginFrom("10.0.0.1")).isEqualTo(429);
    }

    @Test
    @DisplayName("429 carries Retry-After, so a client knows how long to wait")
    void retryAfterIsSet() throws Exception {
        for (int i = 0; i < 21; i++) failedLoginFrom("10.0.0.9");

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> { });
        assertThat(res.getHeader("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("throttling one address does not throttle everyone else")
    void limitIsPerAddress() throws Exception {
        for (int i = 0; i < 21; i++) failedLoginFrom("10.0.0.2");
        assertThat(failedLoginFrom("10.0.0.2")).isEqualTo(429);
        // A shared office IP being blocked must not take the whole internet
        // with it.
        assertThat(failedLoginFrom("10.0.0.3")).isEqualTo(401);
    }

    @Test
    @DisplayName("a successful sign-in clears the counter")
    void successResets() throws Exception {
        for (int i = 0; i < 19; i++) failedLoginFrom("10.0.0.4");
        assertThat(successfulLoginFrom("10.0.0.4")).isEqualTo(200);
        // Someone who mistyped nineteen times and then got it right is not one
        // failure away from being locked out.
        for (int i = 0; i < 20; i++) {
            assertThat(failedLoginFrom("10.0.0.4")).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("BYPASS: a forged X-Forwarded-For does not reset the counter")
    void spoofedForwardedForDoesNotBypass() throws Exception {
        for (int i = 0; i < 21; i++) failedLoginFrom("10.0.0.5");

        // The classic bypass: send a different fake X-Forwarded-For on every
        // request and never hit the limit. The filter reads getRemoteAddr()
        // only, so the header changes nothing.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Forwarded-For", "203.0.113." + System.nanoTime() % 255);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(401));
        assertThat(res.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("the per-email limit is tighter, and independent of the address")
    void emailLimitIsIndependent() {
        for (int i = 0; i < 5; i++) {
            assertThat(filter.emailIsThrottled("victim@example.com")).isFalse();
            filter.noteEmailFailure("victim@example.com");
        }
        // Five failures against one account, from however many addresses.
        assertThat(filter.emailIsThrottled("victim@example.com")).isTrue();
        // And nobody else is affected.
        assertThat(filter.emailIsThrottled("someone.else@example.com")).isFalse();
    }

    @Test
    @DisplayName("the per-email limit is case- and whitespace-insensitive")
    void emailKeyIsNormalised() {
        for (int i = 0; i < 6; i++) filter.noteEmailFailure("Victim@Example.COM");
        // Varying the capitalisation is not a fresh allowance.
        assertThat(filter.emailIsThrottled("  victim@example.com ")).isTrue();
    }

    @Test
    @DisplayName("a successful sign-in clears the email counter too")
    void emailClearedOnSuccess() {
        for (int i = 0; i < 6; i++) filter.noteEmailFailure("back@example.com");
        assertThat(filter.emailIsThrottled("back@example.com")).isTrue();
        filter.clearEmail("back@example.com");
        assertThat(filter.emailIsThrottled("back@example.com")).isFalse();
    }

    @Test
    @DisplayName("only login and register are throttled, not the whole API")
    void otherPathsAreUntouched() throws Exception {
        for (int i = 0; i < 50; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pets");
            req.setRemoteAddr("10.0.0.6");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a 403 (correct password, unapproved account) is NOT counted as a guess")
    void forbiddenIsNotAFailedGuess() throws Exception {
        // A PENDING staff member retrying all morning has already proved the
        // password. Counting those would lock out the one person who is not
        // guessing.
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            req.setRemoteAddr("10.0.0.7");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(403));
            assertThat(res.getStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("registration is capped per address, and only successes count")
    void registrationIsCapped() throws Exception {
        // Rejected sign-ups (bad email, duplicate) must not consume the
        // allowance -- getting a form wrong is not abuse.
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
            req.setRemoteAddr("10.0.0.8");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(400));
            assertThat(res.getStatus()).isEqualTo(400);
        }

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
            req.setRemoteAddr("10.0.0.8");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(201));
            assertThat(res.getStatus()).isEqualTo(201);
        }

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(201));
        assertThat(res.getStatus()).isEqualTo(429);
    }
}
