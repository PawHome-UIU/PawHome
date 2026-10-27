package com.pawhome.backend.security;

import com.pawhome.backend.user.Role;
import com.pawhome.backend.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal: a Spring Security UserDetails that keeps hold of
 * the domain User it came from.
 *
 * Keeping the entity means a controller can ask "which shelter is this person
 * staff at?" without a second query, which is what makes the scope checks in
 * ScopeGuard cheap enough to apply everywhere rather than only where someone
 * remembered.
 */
public class AppUser implements UserDetails {

    private final User user;

    public AppUser(User user) {
        this.user = user;
    }

    public User domain()      { return user; }
    public Long id()          { return user.getId(); }
    public Role role()        { return user.getRole(); }
    /** Null for ADMIN and ADOPTER -- ck_users_staff_shelter guarantees it. */
    public Long shelterId()   { return user.getShelterId(); }

    public boolean is(Role r) { return user.getRole() == r; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(user.getRole().authority()));
    }

    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getEmail(); }

    /**
     * Both flags, exactly as the migration requires.
     *
     * Spring calls this during authentication and refuses the login itself, so
     * a PENDING staff member gets a clean 401 rather than reaching a controller
     * and being stopped by something further in.
     */
    @Override public boolean isEnabled() { return user.isEnabled(); }

    // Nothing in the schema expresses expiry or lockout, so these are true
    // rather than mapped to a column that does not exist. isEnabled() above is
    // the one that carries the real rule.
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
