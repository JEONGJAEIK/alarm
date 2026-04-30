# 알림 발송 시스템 — BE-C 과제

> 비동기/재시도 구조는 [ASYNC_RETRY.md](ASYNC_RETRY.md)에 있습니다.
---

## 1. 프로젝트 개요

수강 신청·결제·취소·강의 시작 등 비즈니스 이벤트를 트리거로 수신자에게 **이메일/인앱 알림**을 비동기 발송하는 알림 서비스입니다. 별도 메시지 브로커 없이 **MySQL을 작업 큐로 사용**하고, 폴링 워커가 발송합니다.

**핵심 보장 3가지:**

| 보장 | 구현 방법 |
|------|----------|
| **멱등 등록** (동일 이벤트 중복 등록 방지) | `dedup_key UNIQUE` 제약을 권위로 사용. 중복 INSERT 시 `DuplicateNotificationException` → 409 Conflict |
| **at-least-once 발송** (발송 누락 방지) | `FOR UPDATE SKIP LOCKED` 클레임 + `StuckClaimSweeper` 자동 복구 |
| **운영 복구** (최종 실패 관리) | DEAD_LETTER 상태 + 관리자 수동 재시도 API |

---

## 2. 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 4.0.6 |
| ORM | Hibernate (JPA) |
| DB | MySQL 8.0 |
| DB 마이그레이션 | Flyway 11.x |
| 테스트 | JUnit 5, Mockito, Awaitility 4.2.2, Testcontainers (MySQL 8.0.36) |

---

## 3. 실행 방법

### 사전 요구사항

- **Java 21** (테스트 포함 모든 경우 필수)
- **Docker** (테스트 전용 MySQL 컨테이너 자동 실행에 필요)

> Docker가 없으면 `./gradlew test` 실행 시 Testcontainers가 MySQL 컨테이너를 띄우지 못해 **테스트 전체가 실패**합니다.
---

### 로컬 서버 실행

> **주의 — 3306 포트 점유 확인:** 로컬에 MySQL Windows 서비스(MySQL80, MySQL84 등) 또는 다른 MySQL 컨테이너가 이미 3306을 점유하고 있으면 아래 docker 명령이 충돌합니다. 또한 그 기존 MySQL에 `alarm` 계정이 없으면 IntelliJ ▶ 화살표 실행 시 `Access denied for user 'alarm'@'localhost' (1045)` 인증 오류가 발생합니다.
>
> 해결 — 다음 중 하나:
> 1. 기존 MySQL 정지: `Stop-Service MySQL80` (관리자 PowerShell, 서비스 이름은 환경별로 다를 수 있음) 후 아래 docker 명령 실행
> 2. 또는 컨테이너의 host 포트를 다른 값으로: `-p 3307:3306` + `SPRING_DATASOURCE_URL`에 3307 명시
> 3. 또는 기존 MySQL에 `alarm` DB·계정 수동 생성: `CREATE DATABASE alarm; CREATE USER 'alarm'@'localhost' IDENTIFIED BY 'alarm'; GRANT ALL ON alarm.* TO 'alarm'@'localhost'; FLUSH PRIVILEGES;`

```bash
# 1. MySQL 컨테이너 띄우기 (3306이 비어 있을 때)
docker run -d \
  --name alarm-mysql \
  -e MYSQL_DATABASE=alarm \
  -e MYSQL_USER=alarm \
  -e MYSQL_PASSWORD=alarm \
  -e MYSQL_ROOT_PASSWORD=root \
  -p 3306:3306 \
  mysql:8.0

# 2. 컨테이너 초기화 5~10초 대기 후 애플리케이션 실행
#    (IntelliJ ▶ 화살표 또는 아래 gradle 명령 — 둘 다 동일)
./gradlew bootRun
```

서버 기동 후 기본 포트: `http://localhost:8080`

**환경 변수 (기본값 변경 시):**

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/alarm?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8&rewriteBatchedStatements=true` | MySQL JDBC URL (격리수준은 application 레벨에서 메서드별로 명시) |
| `SPRING_DATASOURCE_USERNAME` | `alarm` | DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | `alarm` | DB 비밀번호 |

---


## 4. 테스트 실행 방법

```bash
./gradlew test
```
- 외부 MySQL 불필요
- 상세 리포트: `build/reports/tests/test/index.html`

Testcontainers가 MySQL 8.0.36 컨테이너를 자동으로 기동하므로 별도 MySQL 설치 불필요합니다. Docker가 실행 중이어야 합니다.

## 5. 요구사항 해석 및 가정

### 1: "예외를 단순히 무시하지 않으면서 비즈니스 트랜잭션에 영향 주지 않음"

**해석:** 알림 발송 실패가 비즈니스 로직(수강신청, 결제 등)을 롤백시켜서는 안 되며, 동시에 실패를 그냥 버려서도 안 됩니다.
실패시 재시도 로직이 존재해야합니다.

---

### 2. "동일한 이벤트에 대해 중복 발송되면 안 됨" - "발송하지 않는 것이란 완전한 무시를 뜻하는가?"

**해석 및 범위 결정:**

요구사항을 살펴보면 eventId는 전달 측에서 멱등하게 전달이 되지 않습니다.
어떠한 알림이든 한번만 발생해야하고 그 뒤의 알림은 발송하지 않아야 합니다.
그러나 발송하지 않는다는 것이 완전한 무시를 뜻하는가?
이미 알림을 보냈지만 후에 해당 이벤트의 알림 요청이 들어왔을 때 하나의 알림만을 발송하는 것
사용자에게 이 알림을 이미 존재한다 알려야 하는지 예외를 발생시킬지 고민했습니다.

이 서버의 구조를 보아 알림 등록은 사용자의 직접 요청을 받지 않고 서버간 통신으로 작동하는 것으로 생각합니다.
또한 사용자는 이미 자신의 알림 상태를 볼 수 있는 API가 있습니다.
따라서 잘못된 중복 요청은 잘못된 응답 즉 예외를 던지는 것이 옳다고 생각합니다.

---

### 3. 동시에 많은 요청이 들어올 때 실패하여 재시도하면 재시도 또한 많지 않은가?

예를들어 동시에 많은 요청이 들어와서 알림 발송이 많이 실패했습니다.
그럼 지수 백오프와 정해진 기간에 따라 재전송 절차를 밟게 되는데 실패한 알림들을 동일하게 전송하면
실패한 알림또한 대량이기 때문에 또 실패하게 됩니다. 따라서 지터를 설치해야 부하를 분석해야합니다.


---

### 4. "다중 인스턴스 환경 지원"

재시도시 스케줄러의 DB기반 풀링을 사용하고 있습니다.
하지만 다중 인스턴스가 되면 각 인스턴스의 다른 스케줄러가 처리되고 있는 것을 중복처리될 수 있습니다.
Mysql 8.0에 등장한 스킵 락을 사용하여 다른 스케줄러가 처리하고 있는 것을 배제했습니다.


---

### 5. 알림 발송 요청 알림 메시지 미 기재
"이벤트 발생 시 사용자에게 이메일 또는 인앱 알림을 발송해야 합니다." 라는 요구사항과 비즈니스 배경을 파악하기 위해 다른 과제를 참고했지만
알림 메시지가 고정되어있지않아 Map으로 동적으로 받았습니다. 
(보강: 현재 구현은 `referenceData: Map<String, Object>`를 받아 `notification_template`의 `{{key}}` placeholder와 매칭. 템플릿이 없으면 `TemplateRenderer`가 type 이름으로 fallback하여 발송 실패 회피. 동적 메시지 + 운영자 변경 가능한 템플릿이라는 두 요구를 동시에 만족시키는 절충안)

### 6. 알림 확인 권한
보낸이는 상대가 알림을 읽었는지 안 읽었는지도 확인이 가능해야합니다. 
관리자도 수동 재전송을 위해 권한이 주어져야한다고 생각해서 관리자에게도 권한을허용하였습니다. 

### 7. 실패 카운트는 어디에 두어야 하는가? 
알림에 있는 실패 카운트와 DLQ에 있는 실패 카운트는 다른 것을 의미할까요?
저는 관리자가 수동 재시도를 할 수 있다는 것에 주목했습니다.
알림에서 특정 재시도를 실패한 데이터는 DLQ에 보내고 DLQ에서 관리자가 수동 재시도를 하였음에도 실패한 것을 실패 카운트로 두었습니다.

## 6. 설계 결정과 이유

### 스킵락 결정이유

다중 워커 인스턴스가 같은 `PENDING` 행을 동시에 클레임하지 않도록 `SELECT ... FOR UPDATE SKIP LOCKED`로 폴링한다. 한 워커가 잡은 row를 다른 워커는 즉시 건너뛰고 다음 row로 넘어가 throughput 손실 없이 분산 처리된다.

| 옵션 | 채택 여부 | 이유 |
|------|----------|------|
| MySQL `FOR UPDATE SKIP LOCKED` | **채택** | DB row lock이 권위적 동기화. 추가 인프라 0. mission "broker 없이 운영 전환 가능 구조" 정신과 정합 |
| Redis 분산락 (Redlock 등) | 미채택 | 외부 인프라 추가, 알림 시스템에 비대 |
| 메시지 브로커 큐 | 미채택 | mission 명시적으로 "broker 없이 구현" 요구 |
| In-memory worker queue | 미채택 | 워커 crash 시 클레임 정보 손실 → mission "서버 재시작 후 미처리 알림 유실 없이 재처리" 위반 |

`claimBatch`의 `READ_COMMITTED` override와 결합되어 gap lock 부담 없이 동시 INSERT(register API)와 폴링(워커)이 contention 없이 공존한다.

### 낙관락 결정이유

`Notification`에 `@Version` 낙관적 락을 두어 다음 5가지 동시성 시나리오를 한 메커니즘으로 보호한다.

| 시나리오 | 메커니즘 |
|---------|---------|
| 다중 디바이스 동시 markRead (mission 선택 구현) | catch `ObjectOptimisticLockingFailureException` + 재조회 → 멱등 |
| 워커 finalize ↔ stuck-claim sweeper bulk update race | `@Version` 충돌이 `IN_PROGRESS` guard와 결합해 silent skip — 중복 발송 차단 |
| 사용자 markRead ↔ 워커 finalize race | `@Version` 충돌 시 catch & retry |
| revive ↔ 다른 도메인 메서드 race | 동일 |
| 비관적 락의 사각지대(bulk operation) 보강 | sweeper의 bulk UPDATE는 row lock을 획득하지 않지만 `version+1`로 워커 finalize와의 race를 가시화 |

비관적 락(`SELECT ... FOR UPDATE`)은 워커 간 동시 claim 차단에 사용하고, 낙관적 락은 row 단위 작업과 bulk operation 사이의 race 안전망 역할을 한다. 두 락이 다른 사각지대를 커버한다.

### 인앱만 테이블 설정 이유

두 채널의 **발송 결과 위치가 본질적으로 다르다**.

| 채널 | 발송 후 메시지 위치 | 우리 시스템의 책임 |
|------|------------------|-----------------|
| `EMAIL` | 외부 메일박스(Gmail, Outlook 등) | "외부 시스템에 보내고 끝" — 본문 영속 불필요 |
| `IN_APP` | **우리 DB의 `in_app_message`** | "사용자가 우리 앱 inbox에서 조회" — 본문 영속 필수 |

이메일은 SMTP 호출 후 외부에 위임되므로 `notification` 행 하나로 발송 시도 추적이 충분하다. 인앱은 사용자가 우리 앱에서 inbox를 조회하므로 메시지 본문(title, body)이 우리 DB에 영속되어야 하고, 사용자가 본 콘텐츠가 향후 템플릿 변경에도 보존되어야 한다.

### 재시도 횟수 DB 삽입 vs 별도 카운트 이유

"재시도"와 관련된 두 종류 데이터를 위치별로 다르게 보관한다.

| 데이터 | 위치 | 근거 |
|--------|------|------|
| `notification.attempts` (행별 누적 시도 횟수) | **DB 테이블** | 행마다 다름, 매 재시도마다 +1, 영속 필수, 트랜잭션 일관성 필요 |
| `DispatchProperties.maxAttempts`, `backoffBaseMs`, `backoffMaxMs` (시스템 전역 정책) | **`application.yaml`** | 모든 행에 동일, 시작 시 한 번 로드, 환경별 profile 분기, 부팅 시 invariant 검증 |

정책을 DB에 두면 (1) 매 dispatch tick마다 SELECT 부담, (2) 운영자가 `maxAttempts=0` 같은 위험 값을 동적 변경 가능, (3) 환경별 분기 어려움, (4) 테스트 셋업에 row INSERT 강제. config는 Spring Boot의 `@ConfigurationProperties` + 부팅 시 검증으로 안전 + 단순.

원칙: **행별 가변 상태는 DB, 시스템 정책은 config, 알고리즘 자체는 코드**.

### 알림테이블 UUID와 멱등 키 중복으로 둔 이유

`notification` 테이블의 식별자 컬럼들은 **세 가지 다른 책임**을 가진다.

| 컬럼 | 책임 | 누가 결정 |
|------|------|----------|
| `id` (BIGINT auto_increment) | **internal PK** — InnoDB 클러스터 인덱스 효율, FK 참조 (in_app_message) | DB IDENTITY |
| `external_id` (CHAR(16) UNIQUE) | **외부 노출 ID** — API URL의 `{id}` path variable, enumeration 차단 | 시스템 random 생성 |
| `dedup_key` (VARCHAR(200) UNIQUE) | **멱등 게이트** — `lower(eventId) + ":" + channel`, 동일 이벤트 중복 INSERT 차단 | 클라이언트 입력(eventId)으로 합성 |

### 템플릿 ENUM vs 테이블

`notification_template`을 ENUM이 아닌 별도 테이블로 둔 이유.

| 데이터 | 적합한 위치 | 근거 |
|--------|-----------|------|
| `NotificationStatus` (PENDING / IN_PROGRESS / SUCCEEDED / DEAD_LETTER) | **ENUM** | 시스템 상태 머신, 코드 분기와 결합, 운영자가 변경 안 함 |
| `NotificationType` (PAYMENT_CONFIRMED 등) | **ENUM** | 비즈니스 이벤트 분류, 새 타입은 코드 추가 필요 |
| `NotificationChannelType` (EMAIL, IN_APP) | **ENUM** | 채널 구현체와 결합 — 새 채널 = 새 클래스 |
| **메시지 콘텐츠** (title, body 템플릿) | **DB 테이블** | 운영 데이터, 빈번 변경, 비기술자(운영자·CS·번역가) 관리 영역 |

ENUM의 6가지 한계:
1. 코드 배포 없이 메시지 변경 불가 — 운영팀이 직접 관리 못 함
2. A/B 테스트 어려움
3. 다국어 폭발 — `(type, channel, language)` 조합이 늘어나면 enum 멤버 폭증
4. 변경 이력 audit 불가
5. 운영자 권한 분리 불가능
6. 채널 추가 시 모든 enum 멤버에 메서드 추가 필요

DB 테이블로 두면 mission 선택 구현 "**알림 템플릿 관리(타입별 메시지 템플릿)**"에 직접 매핑되며, `referenceData`의 동적 placeholder(`{{key}}`) 시스템과 결합해 자유 확장 가능. 또한 `TemplateRenderer`의 fallback(템플릿 미등록 시 type.name() 사용)이 운영 안전성 확보.

### 별도 DLQ vs 알림 테이블 통합

DLQ를 `notification` 테이블의 `status='FAILED'` 행으로 통합하지 않고 **참조형 분리**(`notification` + `notification_dlq`)를 채택했다.
`notification`은 모든 알림의 진실의 원천(비즈니스 데이터, `dedup_key UNIQUE`, FK 타깃)이고, `notification_dlq`는 "운영자 개입 대기 + 운영자 retry 누적"이라는 워크플로우 상태만 표현한다. 비즈니스 데이터 복사 없이 `notification_id` FK로 참조.

### DLQ 재시도 vs 알림 재시도 불일치
DLQ는 관리자가 수동으로 하는 재시도 알림은 자동 매커니즘으로 인한 재시도
DLQ 진입시 DLQ의 재시도카운트는 초기화하면 불일치가 나타남
그러나 DLQ의 재시도는 관리자가 수동재시도 알림의 재시도는 그동안 실패한 횟수라는 다른 뜻을 가짐


### 사용자 알림 목록
별도의 오래된 알림을 삭제하는 로직이 보이지않음 알림이 많이 쌓였을때 그대로 반환하면 메모리 부하가 강할수있음
페이지로 반환하고 싶지만 요구사항은 목록 따라서 반환은 리스트로 하고 응답은 페이지로 받음
알림 목록 삭제 정책 도입이 필요함 그리고 알림 목록 삭제 정책이 도입되었다해도 그 간격이 최소 1달일텐데 목록으로 보면 UI상 불편이있을 수 있음
페이지로 반환하는게 좋을 듯

### 관리자 수동 발송
데드레터는 많이 쌓일수있기에 페이지로 반환함

### 알림 상태 전이
상태 전이를 줄이기 위해서 처음에는 재시도 카운트의 증가시 알림 상태의 변경은 없다.
처음엔 팬딩으로 둔다 알림 전송을 수행하기직전 프로그레스로 바꾸고 실패해도 프로그레스 상태로 둔다 성공하면 성공으로가고 DLQ로 가면 데드레터로 표시한다.
그리고 DLQ로 갔을때는 데드레터가 아닌 실패로 표시한다.

그러나 멀티 환경에서 다른 워커간의 상황을 표시할 때 복잡해지며 사용자의 UI에도 좋지 않다. 그리고 알림이 재시도 할때마다의 변경을 줄이는 것인데
한번에 성공하는 경우가 대부분이라 성능적 이득이 없다.



---

## 7. 미구현 / 제약사항

| 항목 | 설명 |
|------|------|
| SUCCEEDED 알림 retention 정책 미수립 | hot 테이블에 성공 알림이 영구 누적. 운영 환경 전환 시 N일 이후 archive/delete 배치 필요 |
| DLQ 일괄 revive API 부재 | 외부 채널 장기 장애 후 DLQ에 수백 건이 쌓이면 단건 retry를 반복 호출해야 함. 운영 편의성 측면 개선 후보 |

---

## 8. AI 활용 범위


| 도구 | 모델 | 용도 |
|------|------|------|
| Claude Code (CLI) | Claude Sonnet 4.6 | 일상적 구현, 리팩토링, 테스트 작성, 문서화 |
| Claude Code (CLI) | Claude Opus 4.7 | 설계 검토, 동시성/보안 분석, 복잡한 트레이드오프 판단 |

---

### 워크플로우

#### 1단계: 요구사항 분석 및 스펙 작성

- 과제 요구사항 문서를 AI에 전달하여 기능 명세, 엣지 케이스, 모호한 부분 목록 작성
- 사용자가 각 모호한 부분에 대해 직접 해석 방향 결정 (예: at-least-once vs exactly-once 범위)
- 결정 사항을 반영한 설계 스펙 문서 생성

#### 2단계: 구현 계획 수립

- 설계 스펙을 바탕으로 task 분해 (도메인 엔티티 → 리포지토리 → 서비스 → 디스패처 → API → 테스트)
- 각 task의 의존성 순서 및 테스트 전략 수립

#### 3단계: 태스크별 TDD 구현

- 각 task마다 테스트 파일 먼저 작성 → 구현 코드 작성 → 리팩토링 사이클 반복
- AI가 보일러플레이트 코드(DTOs, 예외 클래스 등) 생성 → 사용자 검토 후 반영
- 핵심 비즈니스 로직(상태 머신, dedup 전략)은 사용자가 명시적으로 검증

#### 4단계: 도메인 전문가 시뮬레이션 리뷰

3개 관점에서 설계 검토를 반복했습니다:

| 검토 관점 | 주요 검토 항목 |
|----------|--------------|
| 동시성 전문가 | `FOR UPDATE SKIP LOCKED` 정확성, REQUIRES_NEW self-invocation 문제, 낙관적 락 충돌 처리 |
| MySQL 전문가 | 격리 수준 (READ-COMMITTED vs REPEATABLE READ), gap lock 영향, 인덱스 커버리지 |
| 분산 시스템 전문가 | visibility timeout 안전성, sweeper 멱등성, 다중 인스턴스 경합 시나리오 |

#### 5단계: 리뷰 결과 반영

각 리뷰 권고에 대해 사용자가 채택/거절을 결정했습니다:

**채택한 권고 예시:**
- 격리 수준 정책 — 다른 AI와의 토론을 거쳐 기본 RR 유지 + 두 메서드만 RC override로 결정 (시스템 전체 RC에서 변경)
- `finalizeSuccess/Failure`에 `lockById` + IN_PROGRESS 가드 추가 (sweeper race 방어)
- `workerId` 100자 절삭 시 뒤에서 자르기 (pid, uuid8 구분력 보존)
- `register`에 `@Transactional` 부여 + 중복 INSERT 시 `DuplicateNotificationException` throw (catch-and-throw가 rollback-only 마킹과 의도가 일치하므로 `UnexpectedRollbackException` 위험 없음)

**거절한 권고 예시:**
- ShedLock 즉시 도입: YAGNI. sweeper 중복 실행은 멱등하여 실해 없음. 문서에 향후 권고로 기록
- `register`에서 catch 후 winner 행 재조회: 호출자가 응답 본문을 필요로 하지 않으므로(별도 조회·목록 API 존재) 단순 throw로 충분 — API 본문은 202 Accepted 빈 응답

---

#### 사용자가 직접 결정한 항목

다음 항목은 AI의 초안이나 제안을 참고했지만 최종 결정은 사용자가 직접 내렸습니다:

| 항목 | 결정 내용 |
|------|----------|
| 중복 방지 범위 | 등록: exactly-once, 발송: at-least-once 로 명시적 분리 |
| 최대 재시도 횟수 | 5회 (운영자 조정 가능) |
| YAGNI 적용 목록 | ShedLock, Micrometer, 브로커 전환 등 생략 결정 |
| 격리 수준 정책 | 기본 RR + 두 메서드만 RC override — 다른 AI와 5단계 재반박 토론 후 최종 결정 (이전 시스템 전체 RC에서 변경) |
| 발송 at-least-once 허용 | "외부 SMTP의 멱등 키 없이는 기술적으로 불가능" 판단 |
| Reviewer 권고 채택/거절 | 위 "거절한 권고 예시" 참조 |

---


#### AI 미사용 영역

다음 항목은 AI 없이 직접 작성했습니다:

- 요구사항 해석 방향 및 범위 결정
- 정책 선택 (YAGNI 판단, 격리 수준 변경 결정)
- Reviewer 권고 채택/거절 판단
- 최종 산출물 검토 및 수정

---

## 9. API 목록 및 예시

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| `POST` | `/api/notifications` | X-User-Id | 알림 등록 (멱등) |
| `GET` | `/api/notifications/{id}` | X-User-Id | 알림 단건 상태 조회 |
| `PATCH` | `/api/notifications/{id}/read` | X-User-Id (본인) | 알림 읽음 처리 |
| `GET` | `/api/users/{userId}/notifications` | X-User-Id (본인) | 사용자 알림 목록 |
| `GET` | `/api/admin/dead-letters` | X-Admin: true | DEAD_LETTER 페이지 목록 (failure_history 포함) |
| `GET` | `/api/admin/dead-letters/{id}` | X-Admin: true | DEAD_LETTER 단건 조회 |
| `POST` | `/api/admin/dead-letters/{id}/retry` | X-Admin: true + X-Admin-Id | DEAD_LETTER 수동 재시도 (X-Admin-Id가 `last_revived_by`로 기록됨) |



### 인증 헤더

| 헤더 | 필수 여부 | 설명 |
|------|----------|------|
| `X-User-Id` | 모든 `/api/**` 필수 | 호출자 식별자. 누락 시 401 |
| `X-Admin` | `/api/admin/**` 필수 | `true` 값 필수. 누락 또는 다른 값이면 403 |
| `X-Admin-Id` | `POST /api/admin/dead-letters/{id}/retry` 필수 | 수동 재시도 명령자 식별자. `notification_dlq.last_revived_by`에 기록 |

### 에러 응답 포맷

```json
{
  "error": "<에러 코드>",
  "message": "<설명>"
}
```

검증 실패(400)는 `fields` 키를 추가합니다:

```json
{
  "error": "validation_failed",
  "message": "검증 실패",
  "fields": {
    "recipientId": "must not be blank",
    "eventId": "must not be blank"
  }
}
```

### 에러 코드 목록

| HTTP 상태 | error 코드 | 발생 조건 |
|----------|-----------|----------|
| 400 | `validation_failed` | Bean Validation 실패 |
| 400 | `bad_request` | IllegalArgumentException (잘못된 enum 값 등) |
| 400 | `missing_header` | 필수 헤더 누락 (예: `X-Admin-Id`). 메시지에 누락 헤더명 포함 |
| 401 | `unauthorized` | X-User-Id 헤더 누락 |
| 403 | `forbidden` | 본인이 아닌 알림 접근 시도, 또는 X-Admin 헤더 누락/false |
| 404 | `not_found` | 알림(또는 DLQ row) 없음 |
| 409 | `duplicate` | 동일 `(eventId, channel)` 조합으로 알림이 이미 등록됨 (POST /api/notifications) |
| 409 | `conflict` | DEAD_LETTER 상태가 아닌 알림에 retry 시도 (IllegalStateException) |
| 500 | `internal_error` | 처리되지 않은 예외 (클래스명만 노출) |

### 공통 타입

| 타입 | 값 목록 |
|------|--------|
| `NotificationType` | `ENROLLMENT_COMPLETED`, `PAYMENT_CONFIRMED`, `COURSE_START_D1`, `CANCELLATION` |
| `NotificationChannelType` | `EMAIL`, `IN_APP` |
| `NotificationStatus` | `PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `DEAD_LETTER` |

### ID 표기 규약

API 응답·요청의 `id` path variable은 **`external_id` (16 hex random)** 입니다. 내부 PK(`BIGINT id`)는 외부에 노출되지 않으며, enumeration 차단을 위한 surrogate identifier입니다.

---

### 1. 알림 등록

#### `POST /api/notifications`

새 알림을 등록합니다. 동일한 `(eventId, channel)` 조합으로 재요청하면 **409 Conflict**(`error: "duplicate"`)을 반환합니다. dedup_key UNIQUE 제약이 멱등성의 권위이며, 호출자는 별도의 조회·목록 API로 알림 상태를 확인합니다.

**요청 헤더:**

| 헤더 | 필수 | 값 |
|------|------|---|
| `X-User-Id` | 필수 | 호출 서비스 식별자 (통상 시스템 서비스 ID) |
| `Content-Type` | 필수 | `application/json` |

**요청 Body:**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `recipientId` | string | 필수 | 수신자 사용자 식별자 |
| `type` | NotificationType | 필수 | 알림 유형 |
| `channel` | NotificationChannelType | 필수 | 발송 채널 |
| `eventId` | string | 필수 | 멱등 키 소스. 동일 이벤트에 항상 같은 값을 전달해야 함 |
| `referenceData` | object | 선택 | 템플릿 렌더링 및 첨부용 자유 JSON 맵 |
| `scheduledAt` | ISO-8601 instant | 선택 | 미래 시각이면 예약 발송. null이면 즉시 처리 후보 |

**요청 예시:**

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-User-Id: system-service" \
  -d '{
    "recipientId": "user-123",
    "type": "PAYMENT_CONFIRMED",
    "channel": "EMAIL",
    "eventId": "enrollment-42-paid",
    "referenceData": { "courseName": "Spring Boot 입문", "amount": 99000 },
    "scheduledAt": null
  }'
```

**응답 202 Accepted:** (응답 본문 없음)

알림이 작업 큐에 PENDING 상태로 등록되었음을 의미합니다. 등록된 알림의 외부 노출 ID(`external_id`)나 상태 추적이 필요하면 `GET /api/users/{userId}/notifications` 또는 `GET /api/notifications/{id}` API를 사용합니다.

**예약 발송 요청 예시:**

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-User-Id: scheduler" \
  -d '{
    "recipientId": "user-123",
    "type": "COURSE_START_D1",
    "channel": "EMAIL",
    "eventId": "enrollment-42-d1",
    "referenceData": { "courseStartAt": "2026-05-01T09:00:00Z" },
    "scheduledAt": "2026-04-30T09:00:00Z"
  }'
```

**중복 요청 응답 (409 Conflict):**

```json
{
  "error": "duplicate",
  "message": "이미 발송된 알림입니다: enrollment-42-paid::EMAIL"
}
```

**에러 응답:**

| 상태 | error 코드 | 설명 |
|------|-----------|------|
| 400 | `validation_failed` | 필수 필드 누락 또는 잘못된 enum 값 |
| 401 | (Spring 기본) | X-User-Id 헤더 누락 |
| 409 | `duplicate` | 동일 `(eventId, channel)` 조합이 이미 등록됨 |

---

### 2. 알림 단건 조회

#### `GET /api/notifications/{id}`

알림 ID(external_id)로 단건을 조회합니다. 본인(recipientId 일치) 또는 관리자(`X-Admin: true`)만 조회 가능합니다.

**경로 파라미터:**

| 파라미터 | 설명 |
|---------|------|
| `id` | 알림 외부 노출 ID (16 hex random) |

**요청 헤더:**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 필수 | 호출자 식별자 |
| `X-Admin` | 선택 | `true`이면 다른 사용자 알림 조회 가능 |

**요청 예시:**

```bash
# 본인 조회
curl -X GET http://localhost:8080/api/notifications/a3f9bc7212de8042 \
  -H "X-User-Id: user-123"

# 관리자 조회
curl -X GET http://localhost:8080/api/notifications/a3f9bc7212de8042 \
  -H "X-User-Id: admin-user" \
  -H "X-Admin: true"
```

**응답 200 OK:**

```json
{
  "id": "a3f9bc7212de8042",
  "recipientId": "user-123",
  "type": "PAYMENT_CONFIRMED",
  "channel": "EMAIL",
  "status": "SUCCEEDED",
  "attempts": 1,
  "referenceData": { "courseName": "Spring Boot 입문", "amount": 99000 },
  "nextAttemptAt": "2026-04-26T12:00:00Z",
  "updatedAt": "2026-04-26T12:00:05Z",
  "read": false,
  "readAt": null
}
```

**에러 응답:**

| 상태 | 설명 |
|------|------|
| 401 | X-User-Id 헤더 누락 |
| 403 | 본인이 아닌 알림을 비관리자가 요청 |
| 404 | 해당 ID의 알림 없음 |

---

### 3. 알림 읽음 처리

#### `PATCH /api/notifications/{id}/read`

알림을 읽음 상태로 처리합니다. 이미 읽음 상태인 경우 멱등하게 현재 상태를 반환합니다.

**경로 파라미터:**

| 파라미터 | 설명 |
|---------|------|
| `id` | 알림 외부 노출 ID |

**요청 헤더:**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 필수 | 호출자 식별자. 반드시 알림 수신자 본인이어야 함 |

**요청 예시:**

```bash
curl -X PATCH http://localhost:8080/api/notifications/a3f9bc7212de8042/read \
  -H "X-User-Id: user-123"
```

**응답 200 OK:**

```json
{
  "id": "a3f9bc7212de8042",
  "recipientId": "user-123",
  "type": "PAYMENT_CONFIRMED",
  "channel": "EMAIL",
  "status": "SUCCEEDED",
  "attempts": 1,
  "referenceData": { "courseName": "Spring Boot 입문" },
  "nextAttemptAt": "2026-04-26T12:00:00Z",
  "updatedAt": "2026-04-26T12:01:00Z",
  "read": true,
  "readAt": "2026-04-26T12:01:00Z"
}
```

**에러 응답:**

| 상태 | 설명 |
|------|------|
| 401 | X-User-Id 헤더 누락 |
| 403 | 본인이 아닌 알림 읽음 처리 시도 |
| 404 | 해당 ID의 알림 없음 |

---

### 4. 사용자 알림 목록 조회

#### `GET /api/users/{userId}/notifications`

수신자 ID로 알림 목록을 조회합니다. 호출자 본인의 목록만 조회 가능합니다.

**경로 파라미터:**

| 파라미터 | 설명 |
|---------|------|
| `userId` | 수신자 사용자 식별자 |

**쿼리 파라미터 (Spring Pageable 표준):**

| 파라미터 | 타입 | 기본값 | 설명 |
|---------|------|--------|------|
| `read` | boolean | (없음) | true/false 시 읽음 여부 필터. 생략 시 전체 반환 |
| `page` | int | 0 | 0-based 페이지 인덱스 |
| `size` | int | 50 | 페이지당 건수. 1~200 사이로 자동 조정 |
| `sort` | string | `createdAt,desc` | Repository 메서드명으로 강제(현재는 호출자 sort 무시) |

**요청 헤더:**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 필수 | path의 userId와 일치해야 함 |

**요청 예시:**

```bash
# 전체 목록 (최근 50건)
curl "http://localhost:8080/api/users/user-123/notifications" \
  -H "X-User-Id: user-123"

# 읽지 않은 알림만 (페이지 사이즈 20)
curl "http://localhost:8080/api/users/user-123/notifications?read=false&size=20" \
  -H "X-User-Id: user-123"
```

**응답 200 OK (List 형식):**

```json
[
  {
    "id": "a3f9bc7212de8042",
    "type": "PAYMENT_CONFIRMED",
    "channel": "EMAIL",
    "status": "SUCCEEDED",
    "read": false,
    "createdAt": "2026-04-26T12:00:00Z",
    "updatedAt": "2026-04-26T12:00:05Z"
  },
  {
    "id": "6ba7b8109dad11d1",
    "type": "ENROLLMENT_COMPLETED",
    "channel": "IN_APP",
    "status": "SUCCEEDED",
    "read": true,
    "createdAt": "2026-04-25T09:00:00Z",
    "updatedAt": "2026-04-25T09:01:00Z"
  }
]
```

> 목록 항목은 `referenceData` 등 무거운 필드를 제외한 요약본입니다 (`NotificationListItem`). 상세 조회는 `GET /api/notifications/{id}`를 사용하세요.
> 사용자 응답이므로 `failureReason`/`failureHistory` 키는 노출되지 않습니다.

**에러 응답:**

| 상태 | 설명 |
|------|------|
| 401 | X-User-Id 헤더 누락 |
| 403 | X-User-Id와 path userId 불일치 |

---

### 5. DEAD_LETTER 페이지 목록 (관리자)

#### `GET /api/admin/dead-letters`

DEAD_LETTER 상태로 진입한 알림과 운영자용 상세 메타(`failureHistory`, `reviveCount` 등)를 페이지로 반환합니다. 정렬은 `notification_dlq.updatedAt DESC`로 강제됩니다.

**요청 헤더:**

| 헤더 | 필수 | 값 |
|------|------|---|
| `X-User-Id` | 필수 | (모든 `/api/**` 공통) |
| `X-Admin` | 필수 | `true` |

**쿼리 파라미터 (Spring Pageable 표준):**

| 파라미터 | 타입 | 기본값 | 설명 |
|---------|------|--------|------|
| `page` | int | 0 | 0-based 페이지 인덱스 |
| `size` | int | 50 | 페이지당 건수 |

> `sort` 파라미터는 무시되며 항상 `updatedAt DESC`로 응답됩니다.

**요청 예시:**

```bash
curl "http://localhost:8080/api/admin/dead-letters?page=0&size=20" \
  -H "X-User-Id: ops-team" \
  -H "X-Admin: true"
```

**응답 200 OK (Spring `Page<AdminDeadLetterListItem>` 형식):**

```json
{
  "content": [
    {
      "notificationExternalId": "a3f9bc7212de8042",
      "recipientId": "user-123",
      "type": "PAYMENT_CONFIRMED",
      "channel": "EMAIL",
      "attempts": 5,
      "reviveCount": 1,
      "firstFailedAt": "2026-04-26T11:00:00Z",
      "lastUpdatedAt": "2026-04-26T11:30:00Z",
      "lastRevivedAt": "2026-04-26T11:25:00Z",
      "lastRevivedBy": "admin42",
      "failureHistory": [
        { "at": "2026-04-26T11:00:00Z", "reason": "SMTP 504 timeout", "reviveCount": 0 },
        { "at": "2026-04-26T11:30:00Z", "reason": "SMTP 550 mailbox not found", "reviveCount": 1 }
      ]
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true
}
```

**에러 응답:**

| 상태 | 설명 |
|------|------|
| 403 | X-Admin 헤더 누락 또는 `true`가 아닌 값 |

---

### 6. DEAD_LETTER 단건 조회 (관리자)

#### `GET /api/admin/dead-letters/{id}`

DEAD_LETTER 상태 알림의 단건 상세를 조회합니다. `failureHistory` 배열 + revive 이력 포함.

**경로 파라미터:**

| 파라미터 | 설명 |
|---------|------|
| `id` | 알림 외부 노출 ID (16 hex) |

**요청 헤더:**

| 헤더 | 필수 | 값 |
|------|------|---|
| `X-User-Id` | 필수 | |
| `X-Admin` | 필수 | `true` |

**응답 200 OK (`AdminDeadLetterResponse`):**

```json
{
  "notificationExternalId": "a3f9bc7212de8042",
  "recipientId": "user-123",
  "type": "PAYMENT_CONFIRMED",
  "channel": "EMAIL",
  "status": "DEAD_LETTER",
  "attempts": 5,
  "reviveCount": 0,
  "referenceData": { "courseName": "Spring Boot 입문" },
  "firstFailedAt": "2026-04-26T11:00:00Z",
  "lastUpdatedAt": "2026-04-26T11:30:00Z",
  "nextAttemptAt": "2026-04-26T11:00:00Z",
  "lastRevivedAt": null,
  "lastRevivedBy": null,
  "failureHistory": [
    { "at": "2026-04-26T11:00:00Z", "reason": "SMTP 504 timeout", "reviveCount": 0 }
  ]
}
```

**에러 응답:**

| 상태 | 설명 |
|------|------|
| 403 | X-Admin 누락 |
| 404 | 알림 또는 DLQ row 없음 |

---

### 7. DEAD_LETTER 수동 재시도 (관리자)

#### `POST /api/admin/dead-letters/{id}/retry`

DEAD_LETTER 알림을 PENDING 상태로 복원합니다. `attempts`가 0으로 초기화되어 재시도 정책이 처음부터 적용됩니다. 동시에 `notification_dlq.revive_count`가 1 증가하고 `last_revived_by`/`last_revived_at`이 갱신됩니다 — `failure_history`는 보존됩니다.

**경로 파라미터:**

| 파라미터 | 설명 |
|---------|------|
| `id` | 재시도할 알림 외부 노출 ID |

**요청 헤더:**

| 헤더 | 필수 | 값 |
|------|------|---|
| `X-User-Id` | 필수 | |
| `X-Admin` | 필수 | `true` |
| `X-Admin-Id` | 필수 | 명령자 식별자 (비어 있으면 400). `last_revived_by` 컬럼에 기록 |

**요청 예시:**

```bash
curl -X POST "http://localhost:8080/api/admin/dead-letters/a3f9bc7212de8042/retry" \
  -H "X-User-Id: ops-team" \
  -H "X-Admin: true" \
  -H "X-Admin-Id: admin42"
```

**응답 200 OK (`AdminDeadLetterResponse`):**

```json
{
  "notificationExternalId": "a3f9bc7212de8042",
  "recipientId": "user-123",
  "type": "PAYMENT_CONFIRMED",
  "channel": "EMAIL",
  "status": "PENDING",
  "attempts": 0,
  "reviveCount": 1,
  "referenceData": { "courseName": "Spring Boot 입문" },
  "firstFailedAt": "2026-04-26T11:00:00Z",
  "lastUpdatedAt": "2026-04-26T12:05:00Z",
  "nextAttemptAt": "2026-04-26T12:05:00Z",
  "lastRevivedAt": "2026-04-26T12:05:00Z",
  "lastRevivedBy": "admin42",
  "failureHistory": [
    { "at": "2026-04-26T11:00:00Z", "reason": "SMTP 504 timeout", "reviveCount": 0 }
  ]
}
```

**에러 응답:**

| 상태 | 설명 |
|------|------|
| 400 | `X-Admin-Id` 헤더 누락 또는 비어 있음 |
| 403 | X-Admin 헤더 누락 또는 `true`가 아닌 값 |
| 404 | 해당 ID의 알림 또는 DLQ row 없음 |
| 409 | 알림이 DEAD_LETTER 상태가 아닐 때 (이미 revive된 경우의 race 등) |

---

### 응답 DTO 필드 설명

#### `NotificationResponse` — 사용자 응답 (단건)

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | string (16 hex) | 알림 외부 노출 ID |
| `recipientId` | string | 수신자 식별자 |
| `type` | NotificationType | 알림 유형 |
| `channel` | NotificationChannelType | 발송 채널 |
| `status` | NotificationStatus | 현재 상태 |
| `attempts` | int | 누적 발송 시도 횟수 |
| `referenceData` | object | 템플릿 렌더링 및 첨부 데이터 |
| `nextAttemptAt` | ISO-8601 | 다음 발송 시도 예정 시각 |
| `updatedAt` | ISO-8601 | 마지막 상태 변경 시각 |
| `read` | boolean | 읽음 여부 |
| `readAt` | ISO-8601 | 읽음 처리 시각 (null이면 미읽음) |

> 사용자 응답에는 실패 사유 키가 절대 포함되지 않습니다 (BOPLA 방어).

#### `NotificationListItem` — 사용자 응답 (목록)

`NotificationResponse`에서 `recipientId`, `attempts`, `referenceData`, `nextAttemptAt`, `readAt`을 제외한 요약 형식.

#### `AdminDeadLetterListItem` — 관리자 응답 (목록 항목)

| 필드 | 타입 | 설명 |
|------|------|------|
| `notificationExternalId` | string (16 hex) | |
| `recipientId` | string | |
| `type` / `channel` | enum | |
| `attempts` | int | notification.attempts (DLQ 진입 시점값) |
| `reviveCount` | int | dlq.revive_count |
| `firstFailedAt` | ISO-8601 | dlq.created_at = DLQ 첫 진입 시각 |
| `lastUpdatedAt` | ISO-8601 | dlq.updated_at = 마지막 갱신 시각 |
| `lastRevivedAt` | ISO-8601 nullable | |
| `lastRevivedBy` | string nullable | 운영자 식별자 |
| `failureHistory` | List<FailureEntry> | `[{at, reason, reviveCount}]` 배열 (최대 10건) |

#### `AdminDeadLetterResponse` — 관리자 응답 (단건/retry)

`AdminDeadLetterListItem`의 모든 필드 + `status`, `referenceData`, `nextAttemptAt`.


---

## 10. 데이터 모델 설명

**테이블 4개:**

| 테이블 | 역할 |
|--------|------|
| `notification` | 알림 발송 단위. 발송 작업 큐 겸용. 상태 머신으로 생명주기 관리. PK는 `BIGINT id`, 외부 노출은 `external_id CHAR(16)` |
| `notification_dlq` | 운영자 전용 DLQ 메타. notification에 `notification_id` FK + UNIQUE (1:1). `failure_history JSON` (최대 10건) + `revive_count` + 낙관적 락 `@Version` |
| `notification_template` | `(type, channel)` UNIQUE. title/body `{{key}}` 템플릿 저장 |
| `in_app_message` | 인앱 채널 발송 결과 inbox. 수신자별 메시지 목록 |

---

### ASCII ERD

```
┌────────────────────────────────────────────────────────┐
│                       notification                      │
│  PK  id               BIGINT AUTO_INCREMENT             │
│  UK  external_id      CHAR(16)        ← API 노출용      │
│      recipient_id     VARCHAR(100)                      │
│      type             VARCHAR(40)                       │
│      channel          VARCHAR(20)                       │
│  UK  dedup_key        VARCHAR(200)                      │
│      reference_data   JSON                              │
│      status           VARCHAR(20)                       │
│      attempts         INT                               │
│      created_at       DATETIME(6)                       │
│      updated_at       DATETIME(6)                       │
│      next_attempt_at  DATETIME(6)                       │
│      claimed_at       DATETIME(6)                       │
│      claimed_by       VARCHAR(100)                      │
│      last_failure_at  DATETIME(6)                       │
│      is_read          BOOLEAN                           │
│      read_at          DATETIME(6)                       │
│      version          BIGINT                            │
└──┬────────────────────────┬─────────────────────────────┘
   │ 1                      │ 1
   │ (notification_id FK)   │ (notification_id FK)
   │                        │ ON DELETE CASCADE
   │ N                      │ 0..1
┌──▼────────────────────┐ ┌─▼────────────────────────────┐
│   in_app_message       │ │      notification_dlq          │
│  PK  id   BIGINT AI    │ │  PK  id              BIGINT AI │
│      recipient_id      │ │  UK  notification_id BIGINT    │
│      notification_id   │ │      failure_history JSON      │
│        BIGINT          │ │      revive_count    INT       │
│      title VARCHAR     │ │      last_revived_at DATETIME  │
│      body  TEXT        │ │      last_revived_by VARCHAR   │
│      created_at        │ │      created_at      DATETIME  │
└────────────────────────┘ │      updated_at      DATETIME  │
                           │      version         BIGINT    │
                           └────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│                  notification_template                │
│  PK  id              BIGINT AUTO_INCREMENT            │
│  UK  (type, channel) UNIQUE                           │
│      type            VARCHAR(40)                      │
│      channel         VARCHAR(20)                      │
│      title_template  VARCHAR(200)                     │
│      body_template   TEXT                             │
└──────────────────────────────────────────────────────┘
```

**관계:**
- `notification` ↔ `in_app_message`: 1:N (논리적 FK. DDL에 FOREIGN KEY 제약 없음 — 성능 및 비동기 insert 순서 유연성 확보. `notification_id BIGINT`로 동일 타입 매핑)
- `notification` ↔ `notification_dlq`: 1:0..1 (`notification_id` UNIQUE + `fk_dlq_notification ... ON DELETE CASCADE`). 운영 정책상 notification은 hard-delete되지 않으므로 cascade는 실 발동되지 않으며, 테스트/관리 도구의 정리 동작을 자연스럽게 지원
- `notification_template`: 독립 참조 테이블. `(type, channel)` 조합으로 조회

---


#### notification

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | BIGINT auto_increment | InnoDB 클러스터 인덱스용 internal PK. 외부에 노출되지 않음 |
| `external_id` | CHAR(16) UNIQUE | API URL의 `{id}` path variable로 노출. UUID hex 32자 중 앞 16자 (≈64-bit entropy). enumeration 차단 |
| `recipient_id` | VARCHAR(100) | 수신자 사용자 식별자 |
| `type` | VARCHAR(40) | 알림 유형 (`ENROLLMENT_COMPLETED`, `PAYMENT_CONFIRMED`, `COURSE_START_D1`, `CANCELLATION`) |
| `channel` | VARCHAR(20) | 발송 채널 (`EMAIL`, `IN_APP`) |
| `dedup_key` | VARCHAR(200) UNIQUE | 중복 제거 키. `lowercase(eventId)::CHANNEL` 형식 |
| `reference_data` | JSON nullable | 템플릿 렌더링 및 클라이언트 표시용 자유 JSON 맵 |
| `status` | VARCHAR(20) | 상태 머신 (`PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `DEAD_LETTER`) |
| `attempts` | INT | 자동 재시도 누적 횟수. revive 시 0으로 리셋 |
| `created_at` | DATETIME(6) | 알림 등록 시각 (UTC) |
| `updated_at` | DATETIME(6) | 마지막 상태 변경 시각 (UTC) |
| `next_attempt_at` | DATETIME(6) | 다음 발송 시도 가능 시각. 워커는 이 시각 이후에만 폴링 |
| `claimed_at` | DATETIME(6) nullable | 워커 클레임 시각. PENDING 상태에서는 NULL |
| `claimed_by` | VARCHAR(100) nullable | 클레임 워커 식별자 (`hostname-pid-uuid8`) |
| `last_failure_at` | DATETIME(6) nullable | 자동 재시도 단계의 마지막 실패 시각 (워커 모니터링용). 사유 텍스트는 영속화하지 않으며 운영자용 상세 사유는 `notification_dlq.failure_history`로 분리 |
| `is_read` | BOOLEAN | 수신자 읽음 여부 (DEFAULT FALSE) |
| `read_at` | DATETIME(6) nullable | 읽음 처리 시각 |
| `version` | BIGINT | JPA `@Version` 낙관적 락 |


#### notification_dlq

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | BIGINT auto_increment | DLQ row PK |
| `notification_id` | BIGINT NOT NULL UNIQUE | 대상 알림 FK. 한 알림당 최대 1 DLQ row. `ON DELETE CASCADE` (V6) |
| `failure_history` | JSON NOT NULL | 운영자용 상세 실패 사유 배열. 각 항목 `{at, reason, reviveCount}`. 상한 10 — 11번째 push 시 가장 오래된 항목 drop. 각 `reason`은 1000자에서 truncate |
| `revive_count` | INT NOT NULL DEFAULT 0 | 운영자 수동 재시도 누적 횟수 |
| `last_revived_at` | DATETIME(6) nullable | 마지막 수동 재시도 시각 |
| `last_revived_by` | VARCHAR(100) nullable | 마지막 수동 재시도 명령자 (POST `/retry`의 `X-Admin-Id` 헤더) |
| `created_at` | DATETIME(6) | DLQ 첫 진입 시각 (`BaseTimeEntity`) |
| `updated_at` | DATETIME(6) | 마지막 갱신 시각 |
| `version` | BIGINT | `@Version` 낙관적 락 (failure_history JSON 동시 수정 방어) |

#### notification_template

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | BIGINT auto_increment | 외부 노출 없는 internal PK |
| `type` | VARCHAR(40) | (notification.type과 동일 enum) |
| `channel` | VARCHAR(20) | (notification.channel과 동일 enum) |
| `title_template` | VARCHAR(200) nullable | 제목 템플릿. `{{key}}` placeholder 지원 |
| `body_template` | TEXT nullable | 본문 템플릿. `{{key}}` placeholder 지원 |

#### in_app_message

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | BIGINT auto_increment | 인앱 inbox row PK (V3) |
| `recipient_id` | VARCHAR(100) | 수신자 식별자 |
| `notification_id` | BIGINT NOT NULL | 연결된 알림 internal id (논리적 FK, V4) |
| `title` | VARCHAR(200) | 렌더링된 메시지 제목 |
| `body` | TEXT | 렌더링된 메시지 본문 |
| `created_at` | DATETIME(6) | 메시지 생성 시각 |

---

### 인덱스 설명

| 인덱스 명 | 테이블 | 컬럼 | 용도 |
|----------|--------|------|------|
| `uq_notification_dedup` | notification | `(dedup_key)` | **중복 등록 방지 핵심 제약.** 멱등 등록의 최종 권위 |
| `uq_notification_external_id` | notification | `(external_id)` | external_id 단건 조회 + UNIQUE 보장 (V4) |
| `ix_notification_pending_due` | notification | `(status, next_attempt_at)` | 폴링 쿼리 커버. `WHERE status='PENDING' AND next_attempt_at <= now ORDER BY next_attempt_at` |
| `ix_notification_recipient` | notification | `(recipient_id, created_at DESC)` | 사용자 알림 목록 기본 조회 |
| `ix_notification_claim` | notification | `(status, claimed_at)` | sweeper 쿼리 커버. `WHERE status='IN_PROGRESS' AND claimed_at < cutoff` |
| `ix_notification_recipient_read` | notification | `(recipient_id, is_read, created_at DESC)` | 읽음 여부 필터 포함 사용자 목록 조회 (V2) |
| `ix_notification_status_updated` | notification | `(status, updated_at DESC)` | (사용 빈도 낮음 — DLQ 페이지 조회는 `notification_dlq` 테이블의 `ix_dlq_updated_at`으로 이전) |
| `uq_dlq_notification` | notification_dlq | `(notification_id)` | 한 알림당 1 DLQ row 보장 + `findByNotificationId` 단건 조회 |
| `ix_dlq_updated_at` | notification_dlq | `(updated_at DESC)` | 관리자 DLQ 페이지 조회 (`Sort.by("updatedAt").descending()` 강제) |
| `fk_dlq_notification` | notification_dlq | `(notification_id) → notification(id) ON DELETE CASCADE` | FK + 정합성 + 자동 정리 |
| `ix_inapp_recipient` | in_app_message | `(recipient_id, created_at DESC)` | 수신자별 인앱 메시지 목록 |
| `ix_inapp_notification_id` | in_app_message | `(notification_id)` | 알림별 인앱 메시지 조회 (V2) |

---