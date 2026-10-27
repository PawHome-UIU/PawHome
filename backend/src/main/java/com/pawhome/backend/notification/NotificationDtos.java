package com.pawhome.backend.notification;

import java.time.LocalDateTime;
import java.util.List;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationRow(Long id, NotificationType type, String title,
                                  String body, String linkUrl,
                                  boolean read, LocalDateTime createdAt) {}

    /**
     * The bell in one response.
     *
     * The count is NOT rows.size(): the list is capped so the dropdown does
     * not fetch a thousand rows, and "23" on the badge with twenty shown is
     * correct in a way that counting the page would not be.
     */
    public record Inbox(long unread, List<NotificationRow> items) {}
}
