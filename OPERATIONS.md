# 알림 발송 시스템 — 운영 가이드

> 본 문서는 **비동기 처리 구조 및 재시도 정책 설명**과 **요구사항 해석 및 개선 의견**을 포함하는 채점 자산입니다.

---

## 1. 시스템 개요

본 시스템은 수강 신청·결제·취소·강의 시작 등 비즈니스 이벤트를 트리거로 수신자에게 이메일/인앱 알림을 비동기 발송하는 Outbox 패턴 기반 알림 서비스입니다. 알림 등록 단계에서 `dedup_key` UNIQUE 제약으로 멱등성을 보장하고, 지수 백오프 재시도 정책 및 StuckClaimSweeper로 워커 장애 상황을 자동 복구하며, 운영자가 DEAD_LETTER 알림을 수동 재시도할 수 있는 관리 API를 제공합니다.

---

## 2. 아키텍처

### 컴포넌트 다이어그램

```
외부 서비스 (BE-A, 스케줄러)
        │
        │ POST /api/notifications
        ▼
┌─────────────────────────────────────────────────┐
│                   api 패키지                     │
│  NotificationController   (등록, 단건 조회)      │
│  UserNotificationController (목록 조회)          │
│  DeadLetterController     (데드레터 관리)         │
│  AdminHeaderInterceptor   (X-Admin 헤더 검증)    │
│  XUserIdArgumentResolver  (X-User-Id 헤더 인증)  │
└───────────────────┬─────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────┐
│               service 패키지                     │
│  NotificationService                            │
│    register / findById / listForRecipient       │
│    markRead / listDeadLetter / retryDeadLetter  │
└───────────────────┬─────────────────────────────┘
                    │
          ┌─────────┴──────────┐
          ▼                    ▼
┌──────────────────┐  ┌────────────────────────────┐
│   domain 패키지   │  │       dispatch 패키지        │
│  Notification    │  │  DispatchWorker (@Scheduled) │
│  (상태 머신)      │  │  PollingNotificationDispatcher│
│  NotificationRepository│  │  DispatchUnitOfWork (REQUIRES_NEW)│
│  DedupKeys       │  │  StuckClaimSweeper           │
│  ReferenceDataConverter│  │  ExponentialBackoffRetryPolicy│
└──────────────────┘  └────────────┬───────────────┘
                                   │
                      ┌────────────┴────────────┐
                      ▼                         ▼
           ┌──────────────────┐    ┌────────────────────┐
           │  channel 패키지   │    │  template 패키지    │
           │  NotificationChannel│  │  NotificationTemplate│
           │  (SPI 인터페이스) │    │  TemplateRenderer   │
           │  EmailChannel(mock)│   └────────────────────┘
           │  InAppChannel(DB) │
           │  ChannelRegistry  │
           └──────────────────┘
                      │
                      ▼
              MySQL (InnoDB)
      notification / in_app_message / notification_template
```

### 패키지·계층 책임

| 패키지 | 주요 클래스 | 책임 |
|--------|------------|------|
| `api` | `NotificationController`, `UserNotificationController`, `DeadLetterController` | REST 엔드포인트. X-User-Id 인증 / X-Admin 관리자 검증 |
| `service` | `NotificationService` | 알림 등록(멱등), 조회, 읽음, 데드레터 관리 |
| `domain` | `Notification`, `NotificationRepository`, `DedupKeys` | JPA 엔티티 + 상태 머신 메서드 + Repository |
| `dispatch` | `PollingNotificationDispatcher`, `DispatchUnitOfWork`, `DispatchWorker`, `StuckClaimSweeper`, `ExponentialBackoffRetryPolicy` | Outbox 폴링, 클레임-발송-finalize, stuck 복구, 재시도 정책 |
| `channel` | `NotificationChannel`(SPI), `EmailChannel`(mock), `InAppChannel`(DB inbox), `ChannelRegistry` | 채널별 실제 발송 어댑터 |
| `template` | `NotificationTemplate`, `TemplateRenderer` | `{{key}}` placeholder 치환으로 title/body 렌더링 |

---

## 3. 트리거 → 알림 매핑 (BE-A 통합 가이드)

다음 이벤트 발생 시 `POST /api/notifications`를 호출하여 알림을 등록합니다.

| 트리거 | 요청 body 예시 |
|--------|---------------|
| `Enrollment.create()` 수강 신청 완료 | `{ "type": "ENROLLMENT_COMPLETED", "eventId": "enrollment-{id}-created", ... }` |
| `Enrollment.confirmPayment()` 결제 확인 | `{ "type": "PAYMENT_CONFIRMED", "eventId": "enrollment-{id}-paid", ... }` |
| 스케줄러 (강의 시작 24h 전) | `{ "type": "COURSE_START_D1", "eventId": "enrollment-{id}-d1", "scheduledAt": "<T-24h>", ... }` |
| `Enrollment.cancel()` 수강 취소 | `{ "type": "CANCELLATION", "eventId": "enrollment-{id}-cancelled", ... }` |

**필수 공통 필드:**

```json
{
  "recipientId": "user-123",
  "type": "ENROLLMENT_COMPLETED",
  "channel": "EMAIL",
  "eventId": "enrollment-42-created",
  "referenceData": { "courseName": "Spring Boot 입문" },
  "scheduledAt": null
}
```

- `eventId`는 재호출 시 동일하게 유지해야 멱등이 보장됩니다 (`dedup_key = lowercase(eventId)::CHANNEL`).
- `X-User-Id` 헤더(호출 서비스 식별자)가 필수입니다.

---

## 4. 상태 머신

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
                                       │                       POST /api/admin/dead-letters/{id}/retry
                        visibility 타임아웃 초과                          │
                        (StuckClaimSweeper)                              ▼
                               │                                      PENDING
                               ▼                                  (attempts=0 초기화)
                            PENDING
                        (attempts 증가 없음)
```

**전이 메서드 요약:**

| 상태 전이 | 호출 메서드 |
|----------|------------|
| PENDING → IN_PROGRESS | `Notification.claim(workerId, now)` |
| IN_PROGRESS → SUCCEEDED | `Notification.markSucceeded(now)` |
| IN_PROGRESS → PENDING (재시도 예약) | `Notification.scheduleRetry(nextAttemptAt, reason, now)` |
| IN_PROGRESS → DEAD_LETTER | `Notification.markDeadLetter(reason, now)` |
| IN_PROGRESS → PENDING (sweeper 복구) | `Notification.releaseStuckClaim(now)` |
| DEAD_LETTER → PENDING (운영자 재시도) | `Notification.revive(now)` |

---

## 5. 비동기 처리 구조

### DB 폴링 + Outbox 패턴 채택 이유

메시지 브로커(Kafka/RabbitMQ) 대신 MySQL 기반 Outbox 패턴을 채택한 이유는 다음과 같습니다.

- **인프라 단순성:** 별도 브로커 없이 MySQL 한 대로 작업 큐와 비즈니스 데이터를 함께 관리.
- **트랜잭션 원자성:** 비즈니스 이벤트와 알림 등록이 같은 MySQL 트랜잭션에 묶이므로 "이벤트는 발생했지만 알림이 누락"되는 상황이 발생하지 않음.
- **운영 복잡도 절감:** 별도 브로커 운영·모니터링 불필요. 과제 범위에 적합.

### `notification` 테이블 = 작업 큐

`notification` 테이블이 메시지 큐 역할을 겸합니다. `status=PENDING, next_attempt_at <= now` 인 행이 미처리 작업을 나타내며, 복합 인덱스 `ix_notification_pending_due (status, next_attempt_at)`로 효율적으로 조회합니다.

### 발송 흐름 (3단계 분리)

```
[1] claimBatch  ─ REQUIRES_NEW 트랜잭션 ─▶ PENDING → IN_PROGRESS (row 반환 후 커밋)
      │
      │  (트랜잭션 종료 — row lock 미보유)
      ▼
[2] channel.deliver()  ─ 트랜잭션 밖 ─▶ 외부 SMTP / in_app_message INSERT
      │
      ▼
[3] finalizeSuccess/Failure  ─ REQUIRES_NEW 트랜잭션 ─▶ SUCCEEDED / PENDING(재시도) / DEAD_LETTER
```

채널 호출(외부 IO)이 트랜잭션 밖에서 실행되므로 SMTP 지연이 DB row lock 누적으로 이어지지 않습니다.

### `DispatchUnitOfWork` — self-invocation 회피

`@Transactional(REQUIRES_NEW)`는 같은 빈의 self-invocation에서는 Spring AOP 프록시를 우회하여 작동하지 않습니다. 이 문제를 해결하기 위해 `claimBatch`, `finalizeSuccess`, `finalizeFailure` 메서드를 `DispatchUnitOfWork`라는 **별도 빈**으로 분리했습니다. `PollingNotificationDispatcher`가 `DispatchUnitOfWork`를 주입받아 호출하므로 프록시가 정상 적용됩니다.

### N 인스턴스 자연 분산

`FOR UPDATE SKIP LOCKED`는 이미 다른 세션이 잠근 행을 건너뛰고 잠금 가능한 행만 반환합니다. 여러 인스턴스가 동시에 `claimBatch`를 호출해도 서로 다른 행을 가져가므로 **leader election 없이 자연 분산**이 이루어집니다.

### `DispatchWorker` (@Scheduled)

`@Scheduled(fixedDelayString = "${alarm.dispatch.poll-interval-ms}")` 어노테이션으로 폴링 주기마다 `dispatcher.runOnce(workerId)`를 호출합니다. 예외는 tick 단위로 흡수하여 다음 폴링이 막히지 않도록 합니다.

---

## 6. 재시도 정책

### ExponentialBackoffRetryPolicy

지수 백오프 + 랜덤 지터(thundering herd 완화) 방식입니다.

```
delay = min(base × 2^(attempts-1), max) × (1 ± jitterRatio)
```

기본 설정값:

| 파라미터 | 기본값 |
|---------|--------|
| `backoff-base-ms` | 1,000 ms (1초) |
| `backoff-max-ms` | 1,800,000 ms (30분) |
| `backoff-jitter-ratio` | 0.2 (±20%) |
| `max-attempts` | 5 |

### 시도 횟수별 백오프 표 (jitter 제외)

| 실패 횟수 (attempts) | 이론 delay | jitter ±20% 범위 |
|---------------------|-----------|-----------------|
| 1 | 1초 | 0.8초 ~ 1.2초 |
| 2 | 2초 | 1.6초 ~ 2.4초 |
| 3 | 4초 | 3.2초 ~ 4.8초 |
| 4 | 8초 | 6.4초 ~ 9.6초 |
| 5 | 16초 → DEAD_LETTER | — |

> `max-attempts=5`이므로 5번째 실패 후 `shouldGiveUp(5)`가 `true`를 반환하여 DEAD_LETTER로 전이됩니다.

### retryable vs non-retryable

`ChannelDeliveryException`의 `isRetryable()` 값으로 구분합니다.

| 구분 | 예시 | 동작 |
|------|------|------|
| `retryable=true` | SMTP 일시 장애, 네트워크 타임아웃 | 백오프 후 PENDING으로 재예약 |
| `retryable=false` | 수신자 존재하지 않음, 잘못된 템플릿 | 즉시 DEAD_LETTER 전이 |
| 예상치 못한 예외 | `NullPointerException` 등 | `retryable=true`로 간주하여 재시도 |

### DEAD_LETTER 전이 조건

다음 두 조건 중 하나를 충족하면 DEAD_LETTER로 전이됩니다.
1. `retryable=false`인 `ChannelDeliveryException` 발생
2. `attempts + 1 >= maxAttempts` (재시도 횟수 소진)

### 운영자 수동 재시도

`POST /api/admin/dead-letters/{id}/retry` 호출 시 `Notification.revive(now)`가 실행됩니다. `attempts`를 **0으로 초기화**하여 재시도 정책이 처음부터 다시 적용됩니다. `last_failure_reason`과 `last_failure_at`도 초기화됩니다.

---

## 7. 멱등성

### dedup_key 파생 방식

```
dedup_key = lowercase(eventId) + "::" + channelName
예: "enrollment-42-created::EMAIL"
```

`DedupKeys.derive(eventId, channel)` 유틸리티가 `Locale.ROOT`로 소문자 변환하므로 대소문자 차이(예: `Enrollment-42-Created` vs `enrollment-42-created`)를 동일하게 처리합니다.

### DB UNIQUE 제약이 권위

`uq_notification_dedup UNIQUE (dedup_key)` 제약이 최종 권위입니다. 애플리케이션 레벨 중복 체크만으로는 race condition을 막을 수 없습니다.

### 동시 등록 — catch-and-refetch 패턴

```
findByDedupKey(key)   → 이미 있으면 반환 (fast path)
   없으면 saveAndFlush(new)
      └─ DataIntegrityViolationException 발생 시
         → findByDedupKey(key) 재조회 → 승자(winner) 행 반환
```

`register` 메서드에 `@Transactional`이 없는 이유: REPEATABLE READ 스냅샷 고정 또는 rollback-only 마킹으로 인해 catch 후 재조회가 실패하기 때문입니다.

### 발송 단계 — at-least-once

등록 단계는 exactly-once이지만, 발송 단계는 at-least-once입니다. 워커가 채널 호출 후 `finalizeSuccess` 전에 사망하면 StuckClaimSweeper가 행을 PENDING으로 복구하여 재발송됩니다. 외부 시스템(SMTP) 입장에서 중복 발송이 가능하며, 이를 방지하려면 외부 시스템의 멱등 키 협조가 필요합니다.

---

## 8. 다중 인스턴스 / 운영 복구

### 워커 자연 분산

`FOR UPDATE SKIP LOCKED` 덕분에 여러 인스턴스가 동시에 `claimBatch`를 호출해도 서로 다른 행만 가져갑니다. 특별한 리더 선출(leader election) 없이 인스턴스를 늘리면 처리량이 수평으로 확장됩니다.

### 워커 ID 형식

```
hostname-pid-uuid8
예: "api-server-01-12345-a3f9bc72"
```

`hostname-pid-uuid8` 형식이며, `claimed_by` 컬럼 길이(100자) 초과 시 우측에서 잘라 보존합니다(`DispatchWorker.buildWorkerId()`).

### StuckClaimSweeper — visibility timeout 복구

`@Scheduled(fixedDelayString = "${alarm.dispatch.sweep-interval-ms}")` 주기마다 실행됩니다.

```sql
-- 의미적으로 동등한 동작 (실제 구현은 JPQL bulk UPDATE)
UPDATE notification
SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
WHERE status = 'IN_PROGRESS'
  AND claimed_at < (now - visibilityTimeoutSeconds)
```

- 복구 시 `attempts`는 증가하지 않습니다 (실제 채널 실패가 아니므로).
- 다중 인스턴스에서 동시 실행되어도 InnoDB row lock으로 직렬화되며 멱등합니다.
- visibility timeout 기본값: **60초**

### 서버 재시작 후 자동 복구

재시작 직전 IN_PROGRESS 상태였던 행은 StuckClaimSweeper가 60초 후 PENDING으로 되돌립니다. Outbox 테이블의 미처리 알림은 영구 보존되므로 서버 재시작 후에도 자동으로 재처리됩니다.

---

## 9. 운영 incident 시나리오

### 시나리오 A: DEAD_LETTER 적체 폭증

| 항목 | 내용 |
|------|------|
| 증상 | `GET /api/admin/dead-letters` 응답 건수가 급증 |
| 원인 | 외부 채널(SMTP 서버 등) 장기 장애 또는 `retryable=false` 예외 다수 발생 |
| 조치 | 1. 외부 채널 장애 복구 후 `POST /api/admin/dead-letters/{id}/retry` 일괄 호출. 2. `last_failure_reason` 확인하여 코드 버그이면 배포 후 재시도. |

### 시나리오 B: 알림 발송 latency 폭증

| 항목 | 내용 |
|------|------|
| 증상 | PENDING → SUCCEEDED 전환 시간이 수분 이상 지연 |
| 원인 | `poll-interval-ms`가 너무 크거나 `batch-size`가 너무 작아 미처리 알림 누적. 또는 외부 채널 응답 지연. |
| 조치 | `alarm.dispatch.poll-interval-ms` 축소 또는 `batch-size` 증가. 채널 timeout 별도 설정. 필요 시 인스턴스 수 증가. |

### 시나리오 C: IN_PROGRESS 행이 sweeper 후에도 누적

| 항목 | 내용 |
|------|------|
| 증상 | `status=IN_PROGRESS` 행이 수분 이상 유지되고 sweeper 실행 후에도 줄어들지 않음 |
| 원인 | `visibility-timeout-seconds`가 채널 호출 시간보다 짧거나, sweeper 스케줄이 비활성화됨 |
| 조치 | `alarm.dispatch.visibility-timeout-seconds` 값을 채널 최대 응답시간보다 크게 설정. sweeper Bean 등록 여부 확인. |

### 시나리오 D: 동일 알림 두 번 발송 (at-least-once)

| 항목 | 내용 |
|------|------|
| 증상 | 수신자가 동일 알림을 두 번 받았다고 신고 |
| 원인 | 워커가 채널 호출 직후, `finalizeSuccess` 전에 사망 → sweeper가 PENDING 복구 → 재발송. 정상적인 at-least-once 동작. |
| 조치 | 수신자에게 양해 안내. 근본 해결은 외부 시스템의 멱등 키(messageId) 협조 필요. |

### 시나리오 E: 동일 이벤트 알림 노이즈 대량 발생

| 항목 | 내용 |
|------|------|
| 증상 | 하나의 이벤트(enrollment-42-created)에 대해 알림이 여러 건 생성됨 |
| 원인 | 호출 측이 `eventId`를 매번 새로 생성하거나 다른 형식으로 전달 |
| 조치 | BE-A 측에서 `eventId`가 동일한 이벤트에 대해 항상 같은 값을 사용하도록 수정. `dedup_key` 규칙(소문자 정규화) 재확인. |

### 시나리오 F: 서버 재시작 후 IN_PROGRESS 잔류 (정상)

| 항목 | 내용 |
|------|------|
| 증상 | 재시작 직후 IN_PROGRESS 행이 다수 존재 |
| 원인 | 재시작 직전 클레임된 행이 새 인스턴스에서 아직 처리되지 않은 정상 상태 |
| 조치 | `visibility-timeout-seconds`(기본 60초) 이후 StuckClaimSweeper가 자동으로 PENDING 복구. 별도 조치 불필요. |

---

## 10. 메시지 브로커 전환 경로

`NotificationDispatcher` 인터페이스를 교체하면 DB 폴링에서 Kafka/RabbitMQ 컨슈머 방식으로 전환할 수 있습니다.

```
현재:
  DispatchWorker (@Scheduled)
    └─ PollingNotificationDispatcher (implements NotificationDispatcher)
         └─ DispatchUnitOfWork.claimBatch / finalizeSuccess / finalizeFailure

전환 후:
  KafkaConsumer / RabbitMQConsumer
    └─ BrokerNotificationDispatcher (implements NotificationDispatcher)
         └─ DispatchUnitOfWork.finalizeSuccess / finalizeFailure (재사용)
```

**전환 시 변경 범위:**

| 컴포넌트 | 변경 여부 | 비고 |
|---------|----------|------|
| `NotificationService.register()` | 변경 없음 | outbox 행 INSERT 그대로 |
| `DispatchUnitOfWork.finalizeSuccess/Failure` | 변경 없음 | 브로커 컨슈머에서 그대로 재사용 |
| `PollingNotificationDispatcher` | 비활성화 | `@ConditionalOnProperty("alarm.dispatch.polling.enabled")` 추가 권장 |
| `DispatchWorker` | 비활성화 | 동일 property로 제어 |
| `StuckClaimSweeper` | 선택적 유지 | 브로커 ack 실패 복구 용도로 유지 가능 |
| `NotificationDispatcher` (인터페이스) | 변경 없음 | 새 구현체로 교체 |

---

## 11. 운영 튜닝 키

`application.yaml`의 `alarm.dispatch.*` 네임스페이스에서 설정합니다.

| 키 | 기본값 | 설명 |
|----|--------|------|
| `poll-interval-ms` | 500 | 폴링 주기 (ms). 낮출수록 latency 감소, DB 부하 증가 |
| `batch-size` | 20 | 한 tick에 클레임할 최대 알림 수 |
| `max-attempts` | 5 | 최대 재시도 횟수. 초과 시 DEAD_LETTER |
| `visibility-timeout-seconds` | 60 | IN_PROGRESS 행이 이 시간 초과 시 sweeper가 PENDING으로 복구 |
| `sweep-interval-ms` | 10000 | StuckClaimSweeper 실행 주기 (ms) |
| `backoff-base-ms` | 1000 | 지수 백오프 기본 지연 (ms) |
| `backoff-max-ms` | 1800000 | 지수 백오프 최대 지연 (ms, 30분) |
| `backoff-jitter-ratio` | 0.2 | 지터 비율 [0,1]. 0이면 지터 없음 |
| `executor-await-seconds` | 30 | graceful shutdown 시 스레드 풀 대기 시간 (초). 테스트 환경에서는 2로 단축 |

---

## 12. 인증

### 사용자 API

모든 `POST /api/notifications`, `GET /api/notifications/{id}`, `PATCH /api/notifications/{id}/read`, `GET /api/users/{userId}/notifications` 요청에 `X-User-Id` 헤더가 필수입니다.

- `X-User-Id` 누락 → **401 Unauthorized**
- 본인이 아닌 사용자의 알림 접근 → **403 Forbidden**

`XUserIdArgumentResolver`가 `@CurrentUser` 파라미터에 헤더 값을 주입하며, 헤더 부재 시 즉시 401을 반환합니다.

### 관리자 API

`/api/admin/**` 경로 모든 요청에 `X-Admin: true` 헤더가 필수입니다.

- `X-Admin` 헤더 누락 또는 `true`가 아닌 값 → **403 Forbidden**

`AdminHeaderInterceptor`가 WebMvcConfigurer에 `/api/admin/**` 패턴으로 등록되어 컨트롤러 진입 전에 검증합니다.

### 운영 환경 전환 시 주의

본 과제는 5일 범위의 간소화된 인증입니다. 운영 환경 전환 시 Spring Security + JWT/OAuth2 도입을 강력히 권장합니다. 현재 방식은 헤더 위조에 취약합니다.

---

## 13. 요구사항 해석 및 개선 의견

### 요구사항 해석

#### "예외를 단순히 무시하지 않으면서 비즈니스 트랜잭션에 영향 주지 않음"

Outbox 패턴과 `REQUIRES_NEW` 트랜잭션 분리로 충족합니다.

- `NotificationService.register()`는 `@Transactional` 없이 `saveAndFlush`의 자체 트랜잭션으로 알림 행을 커밋합니다. **호출자(BE-A)의 트랜잭션 롤백과 완전히 독립적**입니다.
- 발송 실패는 `last_failure_reason`에 기록되고 재시도 또는 DEAD_LETTER로 처리됩니다. 예외가 단순 무시되지 않습니다.
- 비즈니스 이벤트 처리(수강신청 저장 등)와 알림 발송은 별도 트랜잭션이므로 한쪽 실패가 다른 쪽에 전파되지 않습니다.

#### "동일한 이벤트에 대해 중복 발송되면 안 됨"

등록 단계에서 `dedup_key UNIQUE` 제약으로 exactly-once를 보장합니다. 단, 발송 단계는 at-least-once입니다.

- 워커 사망 후 StuckClaimSweeper가 복구하면 외부 시스템(SMTP) 입장에서 중복 발송이 가능합니다.
- Exactly-once 발송은 외부 시스템의 멱등 키(예: SMTP Message-ID) 협조 없이는 기술적으로 불가능합니다.
- 본 시스템은 "중복 등록 방지(exactly-once register)"와 "발송 보장(at-least-once deliver)"을 명확히 분리하여 구현했습니다.

#### 워커 ID 충돌 가능성

`uuid8` = UUID의 앞 8자리 = 약 16^8 ≈ 4억 조합입니다. 단일 인스턴스 내에서는 충돌 확률이 무시할 수준이며, `hostname-pid`가 앞에 붙으므로 실질적 충돌은 **같은 hostname과 같은 pid를 가진 두 프로세스가 동시에 같은 uuid8을 생성**할 때만 발생합니다. 실운영에서는 발생 가능성이 거의 없습니다.

#### 시계 동기화 (NTP) — visibility timeout 안전성

`visibility-timeout-seconds=60`은 NTP drift(일반적으로 ms 단위)보다 충분히 큽니다. 복수 인스턴스 간 시계 오차가 수초 이내라면 sweeper의 `claimed_at < cutoff` 판단에 오류가 생기지 않습니다.

---

### 개선 의견 (운영 환경 전환 시 권고)

다음 항목들은 본 과제 범위에서 YAGNI 원칙으로 생략했으나, 운영 환경에서는 도입을 권장합니다.

| 항목 | 이유 및 권장 방식 |
|------|-----------------|
| **Micrometer 메트릭** | `alarm.dispatch.latency`, `alarm.dead_letter.count`, `alarm.retry.distribution` 등 Gauge/Counter/Timer 등록. Prometheus + Grafana 연동 권장 |
| **ShedLock (sweeper leader election)** | 인스턴스 수 10대 이상 시 sweeper 중복 실행으로 인한 불필요한 DB 부하 발생. ShedLock으로 단일 인스턴스만 실행하도록 제한 |
| **격리 수준 READ_COMMITTED** | 이미 `application.yaml`에 `transaction_isolation='READ-COMMITTED'`가 설정되어 있음. gap lock 회피로 PENDING 행 INSERT 동시성 향상 |
| **브로커 전환 시 `acknowledge()` hook** | Kafka offset commit 또는 RabbitMQ ack를 `finalizeSuccess/Failure` 완료 후 호출하도록 `NotificationDispatcher` 인터페이스에 `acknowledge()` 추가 권장 |
| **채널별 timeout 명시** | 현재 `EmailChannel`은 mock(로그 출력)이므로 timeout 없음. 실 SMTP 연동 시 connection/read timeout 명시 필수 |
| **Spring Security + JWT 도입** | 현재 `X-User-Id`, `X-Admin` 헤더는 위조 가능. 운영 환경에서 JWT 서명 검증 필요 |

---

## 14. 빌드 / 실행

```bash
# 빌드
./gradlew build

# 통합 테스트 실행 (Testcontainers MySQL 자동 실행, Docker 필요)
./gradlew test

# 운영 모드 실행 (외부 MySQL 필요, 환경 변수 설정 후)
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/alarm?serverTimezone=UTC \
SPRING_DATASOURCE_USERNAME=alarm \
SPRING_DATASOURCE_PASSWORD=alarm \
./gradlew bootRun
```

### 환경 변수

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/alarm?...` | MySQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `alarm` | DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | `alarm` | DB 비밀번호 |

### 주요 엔드포인트

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| `POST` | `/api/notifications` | X-User-Id | 알림 등록 (멱등) |
| `GET` | `/api/notifications/{id}` | X-User-Id | 알림 단건 조회 |
| `PATCH` | `/api/notifications/{id}/read` | X-User-Id | 알림 읽음 처리 |
| `GET` | `/api/users/{userId}/notifications` | X-User-Id (본인만) | 알림 목록 조회 |
| `GET` | `/api/admin/dead-letters` | X-Admin: true | DEAD_LETTER 목록 |
| `POST` | `/api/admin/dead-letters/{id}/retry` | X-Admin: true | DEAD_LETTER 재시도 |
