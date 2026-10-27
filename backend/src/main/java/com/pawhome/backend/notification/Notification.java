package com.pawhome.backend.notification;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One line in somebody's bell. Maps `notifications` from V1__init.sql.
 *
 * READ_AT IS THE UNREAD FLAG, and V1 says so in a comment: NULL means unread.
 * A separate boolean would be a second thing to keep in step with the
 * timestamp, and idx_notif_inbox (user_id, read_at, created_at) is built for
 * exactly this shape -- the unread count is an index range scan, not a table
 * scan with a filter.
 *
 * THE TEXT IS STORED, NOT RENDERED FROM THE EVENT. "Bella has been adopted by
 * Ada" is what was true when it was sent; re-deriving it later from live rows
 * would rewrite history every time a pet was renamed or an application
 * withdrawn -- the same argument that makes an adoption application keep its
 * own copy of the applicant's details.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    /** Where the bell takes you. Relative, so it survives a domain change. */
    @Column(name = "link_url", length = 255)
    private String linkUrl;

    /** NULL = unread. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
