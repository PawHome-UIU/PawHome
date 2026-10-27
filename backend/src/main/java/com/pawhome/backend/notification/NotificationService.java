package com.pawhome.backend.notification;

import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.user.User;
import com.pawhome.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.pawhome.backend.notification.NotificationDtos.*;

/**
 * The bell.
 *
 * THE ONE THING TO UNDERSTAND HERE IS THE ORDER OF THE TWO SIDE EFFECTS.
 *
 * Raising a notification does two things that look alike and are not: it
 * WRITES A ROW, and it PUSHES A FRAME down a socket. The row is durable and
 * belongs to the caller's transaction; the frame is fire-and-forget and
 * belongs to nobody.
 *
 *   The row is written INSIDE the caller's transaction, on purpose. An
 *   application is approved, a pet becomes ADOPTED, an adoption row is
 *   written, and the applicant is told -- if that last part were a separate
 *   transaction it could fail on its own and leave somebody's dog rehomed with
 *   nobody informed. One transaction, all of it or none.
 *
 *   The frame is sent AFTER COMMIT, on equal purpose. Pushing inside the
 *   transaction means the browser can be told about a row that then rolls
 *   back: the bell lights, the user clicks, and the inbox is empty. Worse, the
 *   push would win the race often enough to look like it worked. So the send
 *   is registered as an afterCommit callback and never runs if the commit does
 *   not happen.
 *
 * THE PUSHED PAYLOAD IS RE-READ RATHER THAN MAPPED FROM THE SAVED ENTITY.
 * created_at is a database default with insertable=false, so the entity
 * Hibernate hands back still has null there, and a notification with no
 * timestamp sorts to nowhere in the client's list. One primary-key lookup
 * after the commit also means the frame can only ever describe a row that
 * really exists.
 *
 * THREADING: afterCommit runs on the committing thread, and
 * SimpMessagingTemplate hands off to the broker's own executor, so this does
 * not block the request beyond a queue put. It is deliberately NOT @Async --
 * an async push would be scheduled from inside the transaction and could
 * therefore run before the commit it is waiting for, which is the exact bug
 * afterCommit exists to prevent.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** The dropdown's page. The badge counts everything; this caps the list. */
    private static final int INBOX_LIMIT = 20;

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messaging;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    // -------------------------------------------------------------- raising

    /**
     * Tell one person about one thing.
     *
     * Silent when the person has switched that topic off -- see V10. The
     * switch gates DELIVERY, which for these events is the row itself, because
     * the bell is the only channel any of them use.
     *
     * TWO TYPES IGNORE EVERY SWITCH, for the same reason. STAFF_APPROVAL: an
     * account somebody cannot use, with no explanation, is not a quieter
     * experience. SLIP_ISSUED: neither is a bill nobody was told about.
     */
    public void raise(Long userId, NotificationType type,
                      String title, String body, String linkUrl) {
        if (userId == null) return;
        if (!wants(userId, type)) return;

        Notification saved = repository.save(Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .body(body)
                .linkUrl(linkUrl)
                .build());

        pushAfterCommit(userId, saved.getId());
    }

    /** The same thing to every active staff member at one shelter. */
    public void raiseForShelterStaff(Long shelterId, NotificationType type,
                                     String title, String body, String linkUrl) {
        if (shelterId == null) return;
        List<Long> staff = jdbc.queryForList("""
                SELECT id FROM users
                 WHERE role = 'STAFF' AND shelter_id = ?
                   AND is_active = TRUE AND approval_status = 'APPROVED'
                """, Long.class, shelterId);
        staff.forEach(id -> raise(id, type, title, body, linkUrl));
    }

    private boolean wants(Long userId, NotificationType type) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return false;
        return switch (type) {
            case APPLICATION_SUBMITTED, APPLICATION_DECIDED -> user.isNotifyApplications();
            case BOARDING_DECIDED, BOARDING_UPDATE -> user.isNotifyBoarding();
            case CARE_TASK_DUE -> user.isNotifyHealth();
            // Never gated. See the method comment.
            case STAFF_APPROVAL -> true;
            // NOT GATED EITHER, and not under notifyBoarding even though both
            // kinds of slip come from boarding or daycare. Those switches mute
            // CHATTER -- "here is a photo of your dog at lunch". A bill is not
            // chatter, and for the monthly daycare charge this notification is
            // the ONLY thing that tells them: nobody is standing at a counter
            // when a @Scheduled job runs at 03:00 on the 1st. Silently
            // suppressing it means somebody owes money and was never told,
            // which is the same argument STAFF_APPROVAL makes one line up.
            case SLIP_ISSUED -> true;
        };
    }

    /**
     * Register the push, or send it now if there is no transaction to wait for.
     *
     * The no-transaction branch is not a fallback nobody hits: a caller outside
     * a transaction has already committed by definition, so sending
     * immediately is correct there and skipping it would lose the frame.
     */
    private void pushAfterCommit(Long userId, Long notificationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            push(userId, notificationId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                push(userId, notificationId);
            }
        });
    }

    /**
     * Send the frame.
     *
     * WRAPPED IN try/catch AND IT MATTERS. This runs after the commit, so a
     * broker that is down or a client that vanished mid-send must not turn a
     * successful adoption approval into a 500 -- the row is written, the work
     * is done, and the worst case is a bell that lights on the next refresh.
     */
    private void push(Long userId, Long notificationId) {
        try {
            Notification fresh = repository.findById(notificationId).orElse(null);
            if (fresh == null) return;
            messaging.convertAndSendToUser(String.valueOf(userId), "/queue/notifications",
                    new Inbox(repository.countByUserIdAndReadAtIsNull(userId),
                              List.of(toRow(fresh))));
        } catch (RuntimeException e) {
            log.warn("Could not push notification {} to user {}: {}",
                    notificationId, userId, e.toString());
        }
    }

    // -------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public Inbox inbox() {
        Long me = scopeGuard.require().id();
        return new Inbox(
                repository.countByUserIdAndReadAtIsNull(me),
                repository.findRecentForUser(me, INBOX_LIMIT)
                        .stream().map(NotificationService::toRow).toList());
    }

    @Transactional
    public Inbox markAllRead() {
        Long me = scopeGuard.require().id();
        repository.markAllRead(me, LocalDateTime.now(ZoneOffset.UTC));
        return inbox();
    }

    /**
     * Mark one read.
     *
     * The ownership check is the point: the id is a path variable, so without
     * it anyone could mark anyone else's notifications read by guessing.
     */
    @Transactional
    public Inbox markRead(Long id) {
        Long me = scopeGuard.require().id();
        repository.findById(id).ifPresent(n -> {
            scopeGuard.requireOwner(n.getUserId());
            if (n.getReadAt() == null) {
                n.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
                repository.save(n);
            }
        });
        return inbox();
    }

    private static NotificationRow toRow(Notification n) {
        return new NotificationRow(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                n.getLinkUrl(), n.getReadAt() != null, n.getCreatedAt());
    }
}
