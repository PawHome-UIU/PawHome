package com.pawhome.backend.security;

import com.pawhome.backend.user.ApprovalStatus;
import com.pawhome.backend.user.Role;
import com.pawhome.backend.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The tests that matter most in this module.
 *
 * A role test ("can STAFF call this?") is easy to write and easy to pass. The
 * failures that actually leak data are the ones where the role is CORRECT and
 * the row is not theirs -- Sarah at Evergreen Paws reading Tail Waggers'
 * applications, or one adopter reading another's. Those are what this file is
 * about; they are marked LEAK below.
 */
class ScopeGuardTest {

    private static final long EVERGREEN = 1L;
    private static final long TAIL_WAGGERS = 2L;

    private final ScopeGuard guard = new ScopeGuard();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- helpers

    private void signedInAs(User user) {
        AppUser principal = new AppUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }

    private static User admin() {
        return User.builder().id(10L).fullName("Admin Alex").email("alex@pawhome.org")
                .passwordHash("x").role(Role.ADMIN)
                .approvalStatus(ApprovalStatus.APPROVED).emailVerified(true).active(true).build();
    }

    private static User staffAt(long shelterId, long id) {
        return User.builder().id(id).fullName("Staff").email("s" + id + "@shelter.org")
                .passwordHash("x").role(Role.STAFF).shelterId(shelterId)
                .approvalStatus(ApprovalStatus.APPROVED).emailVerified(true).active(true).build();
    }

    private static User adopter(long id) {
        return User.builder().id(id).fullName("Adopter").email("a" + id + "@example.com")
                .passwordHash("x").role(Role.ADOPTER)
                .approvalStatus(ApprovalStatus.APPROVED).emailVerified(true).active(true).build();
    }

    // ------------------------------------------------------- shelter scoping

    @Test
    @DisplayName("staff may act on their own shelter's records")
    void staffOwnShelter() {
        signedInAs(staffAt(EVERGREEN, 20L));
        assertThatCode(() -> guard.requireShelter(EVERGREEN)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LEAK: staff may NOT act on another shelter's records")
    void staffOtherShelterIsRefused() {
        // The role is right. hasRole('STAFF') passes. This is the check that
        // stops it, and without ScopeGuard there is nothing here at all.
        signedInAs(staffAt(EVERGREEN, 20L));
        assertThatThrownBy(() -> guard.requireShelter(TAIL_WAGGERS))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("another shelter");
    }

    @Test
    @DisplayName("a null shelter on the row is refused, not treated as a match")
    void nullShelterOnRowIsRefused() {
        signedInAs(staffAt(EVERGREEN, 20L));
        assertThatThrownBy(() -> guard.requireShelter(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("admin may act on any shelter")
    void adminAnyShelter() {
        signedInAs(admin());
        assertThatCode(() -> guard.requireShelter(EVERGREEN)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireShelter(TAIL_WAGGERS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an adopter has no shelter, so no shelter records are theirs")
    void adopterHasNoShelterScope() {
        signedInAs(adopter(30L));
        assertThatThrownBy(() -> guard.requireShelter(EVERGREEN))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------- owner scoping

    @Test
    @DisplayName("an adopter may act on their own records")
    void adopterOwnRecord() {
        signedInAs(adopter(30L));
        assertThatCode(() -> guard.requireOwner(30L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LEAK: an adopter may NOT act on another adopter's records")
    void adopterOtherRecordIsRefused() {
        // hasRole('ADOPTER') passes for both of them. Only the id separates
        // "my application" from "somebody else's application".
        signedInAs(adopter(30L));
        assertThatThrownBy(() -> guard.requireOwner(31L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not yours");
    }

    @Test
    @DisplayName("LEAK: staff may not claim ownership of an adopter's records")
    void staffIsNotAnOwner() {
        // Staff reach an application through requireShelter, never by passing
        // as the applicant. If this ever passed, a staff member could read an
        // adopter's favourites and quiz answers as though they were their own.
        signedInAs(staffAt(EVERGREEN, 20L));
        assertThatThrownBy(() -> guard.requireOwner(30L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("admin may act on anyone's records")
    void adminAnyOwner() {
        signedInAs(admin());
        assertThatCode(() -> guard.requireOwner(30L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null owner id never matches")
    void nullOwnerIsRefused() {
        signedInAs(adopter(30L));
        assertThatThrownBy(() -> guard.requireOwner(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------- query scoping

    @Test
    @DisplayName("shelterScope is null for admin, meaning every shelter")
    void adminScopeIsUnfiltered() {
        signedInAs(admin());
        assertThat(guard.shelterScope()).isNull();
    }

    @Test
    @DisplayName("shelterScope is the staff member's own shelter")
    void staffScopeIsTheirShelter() {
        signedInAs(staffAt(TAIL_WAGGERS, 21L));
        assertThat(guard.shelterScope()).isEqualTo(TAIL_WAGGERS);
    }

    // ------------------------------------------------------- anonymous

    @Test
    @DisplayName("an anonymous caller is refused, not treated as an owner of null")
    void anonymousIsRefused() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(guard::require).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guard.requireShelter(EVERGREEN)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guard.requireOwner(null)).isInstanceOf(AccessDeniedException.class);
        assertThat(guard.current()).isNull();
    }

    // ------------------------------------------------------- the enabled rule

    @Test
    @DisplayName("an approved, verified, active account IS enabled")
    void fullyEnabled() {
        // The control case. Every other test here asserts isFalse(), so without
        // this one a bug that made isEnabled() always return false would make
        // the whole section pass.
        assertThat(staffAt(EVERGREEN, 60L).isEnabled()).isTrue();
        assertThat(adopter(61L).isEnabled()).isTrue();
        assertThat(admin().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("an unconfirmed email is not enabled, whatever else is set")
    void unverifiedIsNotEnabled() {
        User u = adopter(62L);
        u.setEmailVerified(false);
        assertThat(u.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("all three flags are required: PENDING staff is not enabled")
    void pendingStaffIsNotEnabled() {
        User u = staffAt(EVERGREEN, 22L);
        u.setApprovalStatus(ApprovalStatus.PENDING);
        assertThat(u.isEnabled()).isFalse();
        assertThat(new AppUser(u).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("all three flags are required: a deactivated user is not enabled")
    void deactivatedIsNotEnabled() {
        User u = adopter(33L);
        u.setActive(false);
        assertThat(u.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a rejected sign-up is not enabled even while active")
    void rejectedIsNotEnabled() {
        User u = staffAt(EVERGREEN, 34L);
        u.setApprovalStatus(ApprovalStatus.REJECTED);
        assertThat(u.isEnabled()).isFalse();
    }
}
