package com.pawhome.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Look up by email for login.
     *
     * Emails are stored lower-cased on the way in (UserService normalises
     * them), so callers must lower-case before calling. The column collation is
     * utf8mb4_0900_ai_ci, which is case-INsensitive, so MySQL would match
     * anyway -- normalising on write means the uniqueness constraint and the
     * application agree about what "the same email" means, rather than relying
     * on collation to paper over it.
     *
     * One unique-index lookup on uq_users_email.
     */
    @Query(value = """
           SELECT *
             FROM users
            WHERE email = :email
           """, nativeQuery = true)
    Optional<User> findByEmail(@Param("email") String email);

    /**
     * Is this email already taken?
     *
     * JPQL, NOT NATIVE. COUNT(u) > 0 in JPQL returns a real Boolean; the
     * native equivalent --
     *
     *     SELECT EXISTS (SELECT 1 FROM users WHERE email = ?)
     *
     * -- returns MySQL's BIGINT 0/1, and mapping that onto a `boolean` return
     * type relies on a converter this project does not register.
     *
     * Either way the row is never fetched: the database answers the question
     * instead of handing back a User for the application to throw away.
     */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email")
    boolean existsByEmail(@Param("email") String email);
}
