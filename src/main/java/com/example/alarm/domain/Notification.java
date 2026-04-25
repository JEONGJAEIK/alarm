package com.example.alarm.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "recipient_id", nullable = false, length = 100)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannelType channel;

    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    @Convert(converter = ReferenceDataConverter.class)
    @Column(name = "reference_data", columnDefinition = "JSON")
    private Map<String, Object> referenceData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "last_failure_reason", length = 1000)
    private String lastFailureReason;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Version
    private long version;

    protected Notification() {}

    public static Notification create(String recipientId,
                                      NotificationType type,
                                      NotificationChannelType channel,
                                      String eventId,
                                      Map<String, Object> referenceData,
                                      Instant now) {
        return create(recipientId, type, channel, eventId, referenceData, now, null);
    }

    public static Notification create(String recipientId,
                                      NotificationType type,
                                      NotificationChannelType channel,
                                      String eventId,
                                      Map<String, Object> referenceData,
                                      Instant now,
                                      Instant scheduledAt) {
        Notification n = new Notification();
        n.id = UUID.randomUUID().toString();
        n.recipientId = recipientId;
        n.type = type;
        n.channel = channel;
        n.dedupKey = DedupKeys.derive(eventId, channel);
        n.referenceData = referenceData == null ? Map.of() : Map.copyOf(referenceData);
        n.status = NotificationStatus.PENDING;
        n.attempts = 0;
        n.createdAt = now;
        n.updatedAt = now;
        n.nextAttemptAt = (scheduledAt != null && scheduledAt.isAfter(now)) ? scheduledAt : now;
        n.read = false;
        return n;
    }

    public void claim(String workerId, Instant now) {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException("cannot claim from " + status);
        }
        this.status = NotificationStatus.IN_PROGRESS;
        this.claimedAt = now;
        this.claimedBy = workerId;
        this.updatedAt = now;
    }

    public void markSucceeded(Instant now) {
        this.status = NotificationStatus.SUCCEEDED;
        this.claimedAt = null;
        this.claimedBy = null;
        this.updatedAt = now;
    }

    public void scheduleRetry(Instant nextAttemptAt, String failureReason, Instant now) {
        this.status = NotificationStatus.PENDING;
        this.attempts = this.attempts + 1;
        this.nextAttemptAt = nextAttemptAt;
        this.claimedAt = null;
        this.claimedBy = null;
        this.lastFailureReason = truncate(failureReason);
        this.lastFailureAt = now;
        this.updatedAt = now;
    }

    public void markDeadLetter(String failureReason, Instant now) {
        this.status = NotificationStatus.DEAD_LETTER;
        this.attempts = this.attempts + 1;
        this.claimedAt = null;
        this.claimedBy = null;
        this.lastFailureReason = truncate(failureReason);
        this.lastFailureAt = now;
        this.updatedAt = now;
    }

    public void releaseStuckClaim(Instant now) {
        this.status = NotificationStatus.PENDING;
        this.claimedAt = null;
        this.claimedBy = null;
        this.updatedAt = now;
    }

    public void markRead(Instant now) {
        if (this.read) return;
        this.read = true;
        this.readAt = now;
        this.updatedAt = now;
    }

    public void revive(Instant now) {
        if (this.status != NotificationStatus.DEAD_LETTER) {
            throw new IllegalStateException("can only revive DEAD_LETTER, was " + this.status);
        }
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.lastFailureReason = null;
        this.lastFailureAt = null;
        this.updatedAt = now;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    public String getId() { return id; }
    public String getRecipientId() { return recipientId; }
    public NotificationType getType() { return type; }
    public NotificationChannelType getChannel() { return channel; }
    public String getDedupKey() { return dedupKey; }
    public Map<String, Object> getReferenceData() { return referenceData; }
    public NotificationStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public String getClaimedBy() { return claimedBy; }
    public String getLastFailureReason() { return lastFailureReason; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public boolean isRead() { return read; }
    public Instant getReadAt() { return readAt; }
    public long getVersion() { return version; }
}
