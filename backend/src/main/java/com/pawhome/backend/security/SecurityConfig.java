package com.pawhome.backend.security;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Who may reach what.
 *
 * DENY BY DEFAULT. The last rule is anyRequest().authenticated(), so a
 * controller written tomorrow is protected the moment it exists. Every public
 * route below is public because someone wrote it down here, which means opening
 * something up is a visible decision in a diff rather than an omission.
 *
 * THIS FILE IS ONLY HALF OF AUTHORISATION. It answers "may this kind of user
 * call this URL". It cannot answer "may they touch this row" -- see ScopeGuard
 * for the shelter and ownership checks, which is where the real leaks are.
 *
 * The layers, in the order a request meets them:
 *
 *   1. JwtAuthFilter   who are you?                (and are you still enabled?)
 *   2. these rules     may your role call this URL?
 *   3. @PreAuthorize   finer role rules per method
 *   4. ScopeGuard      may you touch THIS record?
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final LoginRateLimitFilter loginRateLimitFilter;

    @Value("${app.cors.allowed-origins:http://localhost:5174}")
    private String[] allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless Bearer-token API: no auth cookies, so CSRF does not
                // apply. If you ever move the JWT into an httpOnly cookie, CSRF
                // must be re-enabled.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { })
                .headers(headers -> headers
                        .frameOptions(fo -> fo.deny())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // --- the error page ---------------------------------
                        //
                        // A 404 OR A 500 MUST COME BACK AS ITSELF, NOT AS A 401.
                        //
                        // Spring Boot registers this filter chain for the ERROR
                        // dispatch as well as REQUEST, and OncePerRequestFilter
                        // skips error dispatches by default -- so JwtAuthFilter
                        // does NOT run on the way to /error, and the session is
                        // stateless, so the context is empty by the time the
                        // rules below are consulted.
                        //
                        // Without this line every unmatched API path is
                        // authorised as anonymous, falls through to
                        // anyRequest().authenticated(), and comes back 401. The
                        // frontend's axios interceptor reads a 401 as "your
                        // session has expired", clears the token and bounces to
                        // /login -- so calling an endpoint the running backend
                        // does not have logs the user out instead of showing
                        // them an error. That is how a missing route became a
                        // sign-out.
                        //
                        // Permitting the dispatch does not expose anything: the
                        // original request was already authorised (or refused)
                        // on the REQUEST dispatch, and include-message is
                        // on_param, so the body is a status and nothing else.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        // --- genuinely public -------------------------------
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // Reached from a link in an email, by someone who is by
                        // definition not signed in. Each is protected by the
                        // token it carries, not by a session.
                        .requestMatchers(
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                // An invited staff member has no password yet,
                                // so there is nothing to authenticate with.
                                // The one-time INVITE token is the authority.
                                "/api/v1/auth/accept-invite").permitAll()
                        .requestMatchers("/actuator/health", "/api/v1/health").permitAll()

                        // The WebSocket handshake, which carries no identity
                        // and cannot: the browser's WebSocket API gives no way
                        // to set an Authorization header on the opening
                        // request. Authentication happens one frame later, on
                        // STOMP CONNECT -- see WebSocketConfig. Permitting the
                        // handshake grants nothing, because a session whose
                        // CONNECT was refused can neither subscribe nor
                        // receive, and every push is addressed to a principal
                        // that only a valid token can establish.
                        .requestMatchers("/ws/**").permitAll()
                        // The browser sends a preflight with no Authorization
                        // header; refusing it breaks every cross-origin call.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // The guest catalogue. The landing page and /pets are
                        // reachable signed-out by design, so these two GETs are
                        // open -- and the endpoint MUST return the trimmed
                        // projection for an anonymous caller. Withholding
                        // traits and shelter location in the React component
                        // while the API still ships them is a gate anyone can
                        // walk around with devtools.
                        .requestMatchers(HttpMethod.GET, "/api/v1/pets", "/api/v1/pets/*").permitAll()

                        // The images those pets are shown with. Same argument:
                        // a photo on the guest browse page is fetched by a
                        // browser with no token. The stored names are 128
                        // random bits, but that is obscurity, not access
                        // control -- which is why nothing secret may ever be
                        // written under app.uploads.dir.
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/**").permitAll()

                        // The registration form's shelter dropdown. A staff
                        // member picks their shelter BEFORE they have an
                        // account, so this one cannot require a token -- and
                        // until it existed the dropdown was a hard-coded
                        // array whose ids matched nothing, so every staff
                        // sign-up died on fk_users_shelter.
                        //
                        // GET ONLY, and the handler returns ShelterOption --
                        // id, name, city, state. Nothing anonymous should see
                        // a shelter's email, capacity or occupancy.
                        .requestMatchers(HttpMethod.GET, "/api/v1/shelters").permitAll()

                        // --- role-gated areas -------------------------------
                        // Coarse, by URL prefix. Anything needing more than a
                        // role gets @PreAuthorize on the method, and anything
                        // needing a row gets ScopeGuard in the service.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // STAFF only, deliberately. An admin has no shelter_id
                        // (ck_users_staff_shelter), so a staff endpoint has no
                        // shelter to scope them to -- shelterScope() would
                        // return null and hand back every shelter's rows
                        // through an API meant for one. Admin access to the
                        // same data goes through /api/v1/admin/**, where the
                        // shelter is named explicitly.
                        .requestMatchers("/api/v1/staff/**").hasRole("STAFF")

                        // --- everything else --------------------------------
                        .anyRequest().authenticated()
                )

                // Order matters: the rate limiter runs FIRST, so a flood of
                // login attempts is rejected before any of them reaches BCrypt.
                // Behind the JWT filter it would still cost ~100ms of hashing
                // per guess, which is most of the CPU an attacker was after.
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // Without these, an unauthenticated API call gets Spring's HTML
                // login page and the frontend sees a parse error instead of a
                // 401 it can act on.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write(
                                    "{\"status\":401,\"error\":\"Unauthorized\","
                                    + "\"message\":\"Sign in to continue.\"}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write(
                                    "{\"status\":403,\"error\":\"Forbidden\","
                                    + "\"message\":\"You do not have access to that.\"}");
                        })
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt: salted per password and deliberately slow.
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // setAllowedOrigins is EXACT match -- no wildcard support.
        // For LAN/phone testing use the exact origin, e.g. http://192.168.1.20:5174
        config.setAllowedOrigins(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
