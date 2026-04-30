# 비동기 처리 구조 및 재시도 정책
---

## 1. 비동기 처리 구조

### 설계 원칙: DB 기반 작업 큐 + 폴링 디스패처

메시지 브로커(Kafka/RabbitMQ) 대신 MySQL 기반 작업 큐 + 폴링 디스패처를 채택했습니다.

| 결정 | 이유 |
|------|------|
| MySQL 작업 큐 채택 | 인프라 단순성 (별도 브로커 없음), 표준 SQL로 운영 가시성 확보 (조회·통계·백필 즉시 가능), dedup_key UNIQUE 제약으로 재시도 멱등성 보장, 운영 복잡도 절감 |
| 브로커 미사용 | 과제 범위 초과. 단, `NotificationDispatcher` 인터페이스로 추상화되어 향후 전환 가능 |

`notification` 테이블이 **메시지 큐** 역할을 겸합니다. `status=PENDING, next_attempt_at <= now` 행이 미처리 작업을 나타내며, 복합 인덱스 `ix_notification_pending_due (status, next_attempt_at)`로 효율적으로 조회합니다.

---

### 상태 머신

```
             POST /api/notifications
                       │
                       ▼
                    PENDING
               (next_attempt_at 도달 대기)
                       │
       DispatchWorker 폴링 — FOR UPDATE SKIP LOCKED
                       │
                       ▼
                   IN_PROGRESS
                 (claimedBy = workerId)
                  /              \
       채널 호출 성공         채널 호출 실패 (ChannelDeliveryException)
            │                      │
            ▼                      ├── retryable=false  ──────────────┐
        SUCCEEDED               attempts < maxAttempts                │
        (종료)                  retryable=true                        │
                                    │                                 │
                                    ▼                                 ▼
                                 PENDING                         DEAD_LETTER
                          (nextAttemptAt = 백오프)              (종료, 수동 재시도 가능)
                                    │                                 │
                                    │                    POST /api/admin/dead-letters/{id}/retry
                       visibility 타임아웃 초과                       │
                       (StuckClaimSweeper)                           ▼
                              │                                   PENDING
                              ▼                               (attempts=0 초기화)
                           PENDING
                       (attempts 증가 없음)
```

**상태 전이 메서드:**

| 전이 | 호출 메서드 |
|------|-----------|
| PENDING → IN_PROGRESS | `Notification.claim(workerId, now)` |
| IN_PROGRESS → SUCCEEDED | `Notification.markSucceeded(now)` |
| IN_PROGRESS → PENDING (재시도) | `Notification.scheduleRetry(nextAttemptAt, now)` |
| IN_PROGRESS → DEAD_LETTER | `Notification.markDeadLetter(now)` + `DispatchUnitOfWork.recordDeadLetter(...)` (같은 트랜잭션에서 `notification_dlq` INSERT/UPDATE) |
| IN_PROGRESS → PENDING (sweeper) | `Notification.releaseStuckClaim(now)` |
| DEAD_LETTER → PENDING (관리자 수동) | `DeadLetterService.revive(externalId, adminId)` → 내부적으로 `Notification.revive(now)` + `DeadLetter.markRevived(adminId, now)` 같은 트랜잭션 |

> 자동 재시도 단계의 실패 사유는 영속화하지 않고 `log.warn`으로만 기록합니다. DLQ 진입 시점의 사유만 `notification_dlq.failure_history` JSON 배열에 push되며, revive 후 재실패 시 같은 row에 새 항목이 추가됩니다 (상한 10).

---

### 발송 흐름 — 3단계 분리

채널 호출(외부 IO)이 트랜잭션 밖에서 실행됩니다. 외부 SMTP 지연이 DB row lock 누적으로 이어지지 않습니다.

```
[1] claimBatch  ─ REQUIRES_NEW 트랜잭션 ─▶ PENDING → IN_PROGRESS (커밋 후 lock 해제)
      │
      │  (트랜잭션 종료 — row lock 미보유)
      ▼
[2] channel.deliver()  ─ 트랜잭션 없음 ─▶ 외부 SMTP / in_app_message INSERT
      │
      ▼
[3] finalizeSuccess/Failure  ─ REQUIRES_NEW 트랜잭션 ─▶ SUCCEEDED / PENDING(재시도) / DEAD_LETTER
```

**관련 클래스:**

| 클래스 | 역할 |
|--------|------|
| `DispatchWorker` | `@Scheduled(fixedDelayString)` 주기로 `dispatcher.runOnce(workerId)` 호출 |
| `PollingNotificationDispatcher` | `claimBatch` → `channel.deliver()` → `finalizeSuccess/Failure` 흐름 조율 |
| `DispatchUnitOfWork` | `REQUIRES_NEW` 트랜잭션 단위 격리. self-invocation 방지를 위해 별도 빈으로 분리 |
| `NotificationRepository.findDuePending` | `FOR UPDATE SKIP LOCKED` 쿼리로 PENDING 행 클레임 |

---

### DispatchUnitOfWork — self-invocation 문제 해결

`@Transactional(REQUIRES_NEW)`는 같은 빈의 self-invocation에서 Spring AOP 프록시를 우회하여 작동하지 않습니다. `PollingNotificationDispatcher`에서 직접 메서드를 호출하면 `REQUIRES_NEW`가 무시됩니다.

**해결:** `claimBatch`, `finalizeSuccess`, `finalizeFailure` 메서드를 `DispatchUnitOfWork`라는 별도 빈으로 분리하여 주입받아 호출합니다.

---

### 다중 인스턴스 자연 분산

`FOR UPDATE SKIP LOCKED`는 이미 다른 세션이 잠근 행을 건너뛰고 잠금 가능한 행만 반환합니다.

```sql
SELECT * FROM notification
WHERE status = 'PENDING' AND next_attempt_at <= :now
ORDER BY next_attempt_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

여러 인스턴스가 동시에 `claimBatch`를 호출해도 서로 다른 행을 가져가므로 **leader election 없이 자연 분산**이 이루어집니다. 인스턴스를 늘리면 처리량이 선형으로 확장됩니다.

**워커 ID 형식:** `hostname-pid-uuid8`  
예: `api-server-01-12345-a3f9bc72`  
`claimed_by` 컬럼(100자) 초과 시 뒤에서 100자를 보존합니다.

---

## 2. 재시도 정책

### ExponentialBackoffRetryPolicy

지수 백오프 + 랜덤 지터 + multiplier 방식입니다.

```
delay = min(base × multiplier^(attempts-1), max), 그 후 jitter ±r 적용 → [0, max]로 클램프
```

- **지터(jitter):** 여러 워커가 동시에 재시도하여 서버에 집중 부하가 걸리는 thundering herd 문제를 완화합니다.
- **캡(cap):** delay가 max에 도달하면 그 이상으로 증가하지 않습니다.
- **multiplier overflow 방어:** 곱셈 루프에서 `pow > maxMs / multiplier`일 때 즉시 max로 잘라 long overflow 차단.
- **스레드 안전성:** 모든 필드가 불변이고 난수 생성에 `ThreadLocalRandom` 사용.

**기본 설정 (application.yaml):**

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `backoff-base-ms` | 30,000 ms (30초) | 기본 지연 |
| `backoff-max-ms` | 900,000 ms (15분) | 최대 지연 (cap) |
| `backoff-multiplier` | 4 | 곱수 (Java field default는 2, yaml override) |
| `backoff-jitter-ratio` | 0.2 | 지터 ±20% |
| `max-attempts` | 5 | 최대 시도 횟수 |

**시도 횟수별 백오프 (jitter 제외):**

| 실패 횟수 (attempts) | base × 4^(n-1) | cap 적용 | jitter ±20% 범위 |
|---------------------|---------------|---------|-----------------|
| 1 | 30초 | 30초 | 24초 ~ 36초 |
| 2 | 2분 | 2분 | 96초 ~ 144초 |
| 3 | 8분 | 8분 | 384초 ~ 576초 |
| 4 | 32분 | **15분 cap** | 720초 ~ 1080초 |
| 5 | (5회차 실패 후 `shouldGiveUp(5)=true`) | — | DEAD_LETTER 진입 |

> `max-attempts=5`이므로 5번째 실패 후 `shouldGiveUp(5)`가 `true`를 반환하여 DEAD_LETTER로 전이됩니다. 평균적으로 5회 시도가 약 25.5분 안에 종료됩니다.

---

### retryable vs non-retryable

`ChannelDeliveryException`의 `isRetryable()` 값으로 구분합니다.

| 구분 | 예시 | 동작 |
|------|------|------|
| `retryable=true` | SMTP 일시 장애, 네트워크 타임아웃 | 백오프 후 PENDING 재예약 |
| `retryable=false` | 수신자 없음, 잘못된 템플릿 | 즉시 DEAD_LETTER 전이 |
| 예상치 못한 예외 | `NullPointerException`, 기타 | `retryable=true`로 간주하여 재시도 |

---

### DEAD_LETTER 전이 로직

```java
int nextAttempts = n.getAttempts() + 1;
if (!retryable || retryPolicy.shouldGiveUp(nextAttempts)) {
    n.markDeadLetter(now);
    recordDeadLetter(n.getId(), reason, now);  // 같은 트랜잭션에서 dlq INSERT/UPDATE
    log.warn("notification dead-letter id={} reason={}", id, reason);
} else {
    Instant nextAt = retryPolicy.nextAttemptAt(nextAttempts, now);
    n.scheduleRetry(nextAt, now);
    log.warn("notification retry scheduled id={} attempts={} reason={}", id, nextAttempts, reason);
}
```

두 조건 중 하나를 충족하면 DEAD_LETTER로 전이됩니다:
1. `retryable=false`인 `ChannelDeliveryException` 발생
2. `attempts + 1 >= maxAttempts` (재시도 횟수 소진)

`recordDeadLetter`는 같은 `@Transactional(REQUIRES_NEW)` 안에서 실행되어 `notification.markDeadLetter`와 dlq INSERT/UPDATE가 원자적으로 commit됩니다. 같은 알림이 revive 후 재실패하면 기존 dlq row에 `failure_history`가 push됩니다.

```java
private void recordDeadLetter(Long notificationId, String reason, Instant now) {
    var existing = dlqRepo.findByNotificationId(notificationId);
    if (existing.isPresent()) {
        existing.get().appendFailure(reason, now);  // dirty checking
    } else {
        dlqRepo.saveAndFlush(DeadLetter.create(notificationId, reason, now));
    }
}
```

> `lockById(PESSIMISTIC_WRITE)` + `claimBatch`의 `FOR UPDATE SKIP LOCKED`가 같은 알림에 대한 동시 진입을 차단하므로 dlq INSERT의 UNIQUE 위반 race는 실제로 도달할 수 없습니다.

---

### 운영자 수동 재시도 (revive)

`POST /api/admin/dead-letters/{id}/retry` (X-Admin + X-Admin-Id 헤더 필수) 호출 시 `DeadLetterService.revive(externalId, adminId)`가 같은 `@Transactional` 안에서 두 row를 갱신합니다:

```java
// Notification.revive(now) — status/attempts 초기화
this.status = NotificationStatus.PENDING;
this.attempts = 0;
this.nextAttemptAt = now;
this.lastFailureAt = null;

// DeadLetter.markRevived(adminId, now) — dlq 메타 갱신, failure_history 보존
this.reviveCount = this.reviveCount + 1;
this.lastRevivedAt = now;
this.lastRevivedBy = adminId;
```

`attempts`를 0으로 초기화하여 재시도 정책이 처음부터 재적용되며, dlq의 `revive_count`는 누적 증가합니다. `failure_history`는 보존되어 운영자가 과거 실패 패턴을 계속 조회할 수 있습니다. `notification.@Version` 낙관적 락이 두 관리자의 동시 revive 시 한 쪽만 성공하도록 보장합니다.

---

## 3. 멱등성

### 등록 단계 — exactly-once

```
dedup_key = lowercase(eventId) + "::" + channelName
예: "enrollment-42-created::EMAIL"
```

**catch-and-throw 패턴 (DB UNIQUE 제약이 권위):**

```java
@Transactional
public void register(RegisterCommand cmd) {
    Notification n = Notification.createImmediate(...);
    try {
        repo.saveAndFlush(n);
    } catch (DataIntegrityViolationException dup) {
        throw new DuplicateNotificationException(n.getDedupKey()); // → 409 Conflict
    }
}
```

`dedup_key UNIQUE` 제약이 멱등성의 단일 권위입니다. 호출자가 같은 `(eventId, channel)` 조합으로 재호출하면 두 번째 호출은 INSERT가 unique 위반으로 실패하고, 그것을 catch하여 `DuplicateNotificationException`(API에서 409 Conflict로 변환)을 던집니다.

**`@Transactional`이 안전한 이유:** `saveAndFlush`가 unique 위반으로 실패하면 Hibernate가 트랜잭션을 rollback-only로 마킹합니다. 일반적으로는 같은 트랜잭션에서 commit을 시도하면 `UnexpectedRollbackException`이 터지지만, 본 메서드는 catch 직후 즉시 throw하므로 Spring AOP는 commit을 시도하지 않고 rollback을 수행합니다(rollback-only 마킹과 우리 의도가 일치). 따라서 `@Transactional`을 붙여도 안전합니다.

> 호출자가 자신의 `@Transactional` 안에서 `register`를 호출하면 동일 트랜잭션에 합류합니다. 비즈니스 트랜잭션 커밋 후에만 알림을 발생시키려면 `@TransactionalEventListener(phase=AFTER_COMMIT)` 패턴을 사용합니다.

### 발송 단계 — at-least-once

등록은 exactly-once이지만, **발송은 at-least-once**입니다.

워커가 채널 호출 후 `finalizeSuccess` 전에 사망하면 StuckClaimSweeper가 행을 PENDING으로 복구하여 재발송됩니다. 외부 시스템(SMTP) 입장에서 중복 발송이 가능하며, 이를 방지하려면 외부 시스템의 멱등 키(SMTP Message-ID 등) 협조가 필요합니다.

---

## 4. 다중 인스턴스 운영 복구

### StuckClaimSweeper — visibility timeout 복구

워커가 채널 호출 중 OOM/SIGKILL/네트워크 단절로 사망한 경우 행을 복구합니다.

```sql
-- 의미적으로 동등한 동작 (실제 구현은 JPQL bulk UPDATE)
UPDATE notification
SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL,
    updated_at = :now, version = version + 1
WHERE status = 'IN_PROGRESS'
  AND claimed_at < (now - visibilityTimeoutSeconds)
```

**동작 특성:**
- 복구 시 `attempts`는 증가하지 않습니다 (실제 채널 실패가 아니므로)
- 다중 인스턴스에서 동시 실행되어도 InnoDB row lock으로 직렬화 — 멱등
- `@Scheduled(fixedDelayString = "${alarm.dispatch.sweep-interval-ms}")` 주기로 실행 (기본 10초)
- visibility timeout 기본값: **60초**

### finalizeSuccess/Failure의 IN_PROGRESS 가드

```java
Notification n = repo.lockById(id).orElseThrow(...); // PESSIMISTIC_WRITE + timeout=0 (NOWAIT)
if (n.getStatus() != NotificationStatus.IN_PROGRESS) {
    log.warn("skip — sweeper race or duplicate call");
    return;
}
```

sweeper가 이미 PENDING으로 되돌린 경우 또는 다른 워커가 재클레임한 경우 상태가 IN_PROGRESS가 아니므로 조용히 skip하여 상태 오염을 방지합니다.

### 서버 재시작 후 자동 복구

재시작 직전 IN_PROGRESS 상태였던 행은 `visibility-timeout-seconds`(기본 60초) 이후 StuckClaimSweeper가 자동으로 PENDING으로 복구합니다. 작업 큐 테이블의 미처리 알림은 영구 보존되므로 서버 재시작 후에도 자동으로 재처리됩니다.

---

## 5. 설정 파라미터

`application.yaml`의 `alarm.dispatch.*` 네임스페이스에서 설정합니다.

| 키 | 기본값 | 설명 |
|----|--------|------|
| `poll-interval-ms` | 500 | 폴링 주기 (ms). 낮출수록 latency 감소, DB 부하 증가 |
| `batch-size` | 20 | 한 tick에 클레임할 최대 알림 수 |
| `max-attempts` | 5 | 최대 재시도 횟수. 초과 시 DEAD_LETTER |
| `visibility-timeout-seconds` | 60 | IN_PROGRESS 행이 이 시간 초과 시 sweeper가 복구 |
| `sweep-interval-ms` | 10000 | StuckClaimSweeper 실행 주기 (ms) |
| `backoff-base-ms` | 1000 | 지수 백오프 기본 지연 (ms) |
| `backoff-max-ms` | 1800000 | 지수 백오프 최대 지연 (ms, 30분) |
| `backoff-jitter-ratio` | 0.2 | 지터 비율 [0,1] |
| `executor-await-seconds` | 30 | graceful shutdown 대기 시간 (초) |
