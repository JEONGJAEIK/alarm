package com.example.alarm.service;

import com.example.alarm.domain.DeadLetter;
import com.example.alarm.domain.DeadLetterRepository;
import com.example.alarm.domain.Notification;
import com.example.alarm.domain.NotificationRepository;
import com.example.alarm.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DLQ(notification_dlq) 운영 작업의 단일 진입점.
 *
 * <p>관리자 화면의 페이지 조회·단건 조회·수동 재시도(revive)를 담당한다.
 * 자동 DLQ 진입은 {@code DispatchUnitOfWork.finalizeFailure}가 직접 처리하며,
 * 본 서비스는 그 이후의 운영자 행위만 다룬다.
 */
@RequiredArgsConstructor
@Service
public class DeadLetterService {

    private final NotificationRepository notifRepo;
    private final DeadLetterRepository dlqRepo;
    private final Clock clock;

    /**
     * DLQ 페이지 조회. 정렬은 강제로 {@code updatedAt DESC} (호출자 sort 무시).
     *
     * @param pageable 호출자 페이지 정보 (sort는 무시)
     * @return DLQ 페이지 (notification 매핑은 {@link DeadLetterView}로 결합)
     */
    @Transactional(readOnly = true)
    public Page<DeadLetterView> list(Pageable pageable) {
        Pageable forced = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<DeadLetter> page = dlqRepo.findAll(forced);
        Map<Long, Notification> byId = loadNotifications(page);
        return page.map(d -> new DeadLetterView(byId.get(d.getNotificationId()), d));
    }

    /**
     * 외부 노출 ID로 DLQ 단건 조회.
     *
     * @param externalId 알림의 외부 노출 ID
     * @return notification + dlq 결합 view
     * @throws NotificationNotFoundException 알림 또는 dlq row가 없을 때
     */
    @Transactional(readOnly = true)
    public DeadLetterView findOne(String externalId) {
        Notification n = notifRepo.findByExternalId(externalId)
                .orElseThrow(() -> new NotificationNotFoundException(externalId));
        DeadLetter d = dlqRepo.findByNotificationId(n.getId())
                .orElseThrow(() -> new NotificationNotFoundException(externalId));
        return new DeadLetterView(n, d);
    }

    /**
     * DLQ 알림을 PENDING으로 되살린다(수동 재시도).
     *
     * <p>notification은 {@link Notification#revive}로 PENDING + attempts=0,
     * dlq는 {@link DeadLetter#markRevived}로 revive_count++. failure_history는 보존된다.
     * 두 row는 같은 트랜잭션에서 dirty checking으로 함께 갱신된다.
     *
     * @param externalId 알림의 외부 노출 ID
     * @param adminId    수동 재시도 명령을 발행한 관리자 식별자 (last_revived_by에 기록)
     * @return revive 처리된 결합 view
     * @throws NotificationNotFoundException 알림이 없을 때
     * @throws IllegalStateException         알림이 DEAD_LETTER 상태가 아니거나 dlq row가 누락됐을 때
     */
    @Transactional
    public DeadLetterView revive(String externalId, String adminId) {
        Notification n = notifRepo.findByExternalId(externalId)
                .orElseThrow(() -> new NotificationNotFoundException(externalId));
        if (n.getStatus() != NotificationStatus.DEAD_LETTER) {
            throw new IllegalStateException(
                    "DEAD_LETTER 상태의 알림만 revive할 수 있습니다 (현재: " + n.getStatus() + ")");
        }
        DeadLetter d = dlqRepo.findByNotificationId(n.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "DEAD_LETTER 알림인데 dlq row가 없습니다 notification_id=" + n.getId()));
        Instant now = Instant.now(clock);
        n.revive(now);
        d.markRevived(adminId, now);
        return new DeadLetterView(n, d);
    }

    private Map<Long, Notification> loadNotifications(Page<DeadLetter> page) {
        if (page.isEmpty()) return Map.of();
        var ids = page.getContent().stream().map(DeadLetter::getNotificationId).toList();
        return notifRepo.findAllById(ids).stream()
                .collect(Collectors.toUnmodifiableMap(Notification::getId, n -> n));
    }

    /**
     * notification + dlq 결합 view. 컨트롤러 매퍼에서 두 객체를 동시에 받기 위함.
     */
    public record DeadLetterView(Notification notification, DeadLetter deadLetter) {}
}
