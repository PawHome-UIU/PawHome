package com.pawhome.backend.security;

import com.pawhome.backend.user.User;
import com.pawhome.backend.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Reads the Bearer token, loads the user, and puts them in the security
 * context. Runs once per request.
 *
 * IT LOADS THE USER ROW EVERY TIME, ON PURPOSE. The alternative -- trusting
 * role and shelter_id signed into the token -- avoids one indexed primary-key
 * lookup and buys a 24-hour window in which:
 *
 *   - a deactivated account still works;
 *   - a rejected staff sign-up still works;
 *   - a staff member moved between shelters still reads the old shelter's
 *     applications, pets and boarding records.
 *
 * One primary-key lookup per request is not a cost worth that. If it ever
 * becomes one, the fix is a cache with an eviction hook on user updates -- not
 * putting authorisation data in the token.
 *
 * THIS FILTER NEVER REJECTS ANYTHING. A missing, malformed or expired token
 * simply leaves the context empty, and the authorisation rules in
 * SecurityConfig decide what that means for the endpoint being called. Failing
 * here instead would make every public route (the landing page's pet list, the
 * login endpoint itself) unreachable the moment a browser sent a stale token.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(request);

        // Already authenticated (or no token at all): nothing to do here.
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token, request);
        }

        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        JwtService.Verified verified = jwtService.verify(token);
        if (verified == null) return;   // invalid or expired -- stays anonymous
        Long userId = verified.userId();

        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            // A correctly signed token for a user who no longer exists. Worth a
            // line: it means an account was deleted while a token was live.
            log.debug("Valid token for unknown user id {}", userId);
            return;
        }

        User user = found.get();

        // The two-flag rule, checked on EVERY request rather than only at login.
        // This is the whole point of loading the row: an admin deactivating
        // someone takes effect immediately, not when their token expires.
        if (!user.isEnabled()) {
            log.debug("Rejected an enabled-check for user {} (active={}, approval={})",
                    user.getId(), user.isActive(), user.getApprovalStatus());
            return;
        }

        // V11's retirement check, and it costs NOTHING extra: the user row is
        // already loaded above for the enabled test. A token minted before the
        // owner last changed their password is refused however long it has
        // left to run -- which is what makes changing a password on a shared
        // machine actually end the session somebody left open on it.
        //
        // Seconds, not milliseconds. A JWT's iat claim is whole seconds and the
        // column is DATETIME, so a token issued in the same second as the
        // change compares equal and survives -- which is deliberate, because
        // that is the fresh token handed back by the very request that made
        // the change.
        if (user.getTokensValidFrom() != null) {
            Instant retiredBefore = user.getTokensValidFrom().toInstant(ZoneOffset.UTC);
            if (verified.issuedAt().isBefore(retiredBefore)) {
                log.debug("Refused a token for user {} issued before their password change",
                        user.getId());
                return;
            }
        }

        AppUser principal = new AppUser(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX)) return null;
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
