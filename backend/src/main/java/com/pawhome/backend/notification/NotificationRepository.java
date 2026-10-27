package com.pawhome.backend.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * The bell's list.
     *
     * READS idx_notif_inbox (user_id, read_at, created_at) -- user_id narrows,
     * created_at is already ordered inside the index, so there is no filesort.
     *
     * THE LIMIT IS A BOUND PARAMETER, not Spring Data's Limit object. Limit is
     * a special argument that only derived queries understand; once the query
     * is written out, the limit has to be written out with it.
     */
    @Query(value = """
           SELECT *
             FROM notifications
            WHERE user_id = :userId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
           """, nativeQuery = true)
    List<Notification> findRecentForUser(@Param("userId") Long userId,
                                         @Param("limit") int limit);

    /**
     * The dot.
     *
     * user_id then read_at is the leftmost prefix of idx_notif_inbox, so this
     * count never touches the table -- it is answered from the index alone.
     */
    @Query(value = """
           SELECT COUNT(*)
             FROM notifications
            WHERE user_id = :userId
              AND read_at IS NULL
           """, nativeQuery = true)
    long countByUserIdAndReadAtIsNull(@Param("userId") Long userId);

    /**
     * Mark everything read in ONE statement.
     *
     * Not a loop of saves: a person with forty unread notifications would be
     * forty updates and forty round trips, and the whole point of the button
     * is that it is one click.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now "
         + " WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
