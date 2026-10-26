# 12장. 다음 공부와 연습 문제

**한 줄 요약**: 이 저장소에서 바로 해볼 수 있는 확장 과제와, 1~11장 연습 문제의 힌트를 모았습니다. 힌트는 답을 다 알려 주지 않습니다. 코드를 직접 고쳐 보고 테스트로 확인하는 것이 가장 빨리 느는 길입니다.

## 1. 다음에 공부할 것

### Kotlin

| 주제 | 왜 | 이 저장소에서 연결되는 곳 |
|---|---|---|
| 코루틴 (`suspend`, structured concurrency, `Flow`) | 스레드를 덜 쓰고 기다리는 코드를 순차 코드처럼 쓴다 | 지금은 스레드 풀(`@Async`)과 스케줄러로 비동기를 처리한다. 같은 일을 코루틴으로 하면 어떻게 다를지 비교해 보기 |
| `inline` + `reified` | 제네릭 타입을 실행 중에도 쓰게 한다 | `runApplication<SiteApplication>()`, `addFilterBefore<...>()` 가 이 기능 |
| DSL 만들기 (수신 객체 지정 람다) | `http { ... }` 같은 문법을 직접 만든다 | Spring Security Kotlin DSL ([6장](06-auth.md)) |
| `value class` | `Long` 하나를 감싸 타입을 구분한다 (회원 ID 와 이벤트 ID 를 섞지 않게) | `issue(couponEventId: Long, memberId: Long)` 의 두 Long 을 바꿔 넣어도 컴파일된다 |

### Spring 과 운영

| 주제 | 왜 | 출발점 |
|---|---|---|
| 가상 스레드 (Java 21, `spring.threads.virtual.enabled`) | 요청 스레드가 I/O 를 기다리는 동안 OS 스레드를 붙잡지 않는다 | 이 저장소는 M5 에서 켰다. 무엇이 무너졌고 어떻게 고쳤는지는 [13장](13-virtual-threads-and-backpressure.md). 다음은 JDK 24+ 의 JEP 491(`synchronized` 고정 해소) |
| 동시 실행 제한 (bulkhead) · 요청 수 제한 (rate limit) | 한 기능이 커넥션 풀을 다 차지하지 않게 / 악의적 반복 요청 막기 | bulkhead 는 M5 에서 했다([13장](13-virtual-threads-and-backpressure.md)). 회원별 rate limit 은 아직 없다 |
| 서버 여러 대 | 이 프로젝트의 캐시·SSE 연결은 서버마다 따로 있다 | `StartedCouponEventCache`, `CouponQueueStatusStreams` 주석 |
| 메시지 브로커 (Kafka 등) | Redis 대기열·Outbox 를 더 큰 규모로 | Outbox 테이블을 읽어 브로커로 보내는 구조(CDC)로 발전 |

### 테스트

| 주제 | 출발점 |
|---|---|
| Kotest 속성 기반 테스트 (`checkAll`) | `Discount.calculate` 에 "할인액은 0 이상, 주문 금액 이하"를 무작위 입력으로 확인해 보기 |
| 장애 주입 | Outbox 테스트처럼 "장애가 남기는 흔적"을 만들어 재현하기 ([8장](08-async-and-reliability.md)) |

공식 문서: [Kotlin](https://kotlinlang.org/docs/home.html) · [Spring Boot](https://docs.spring.io/spring-boot/) · [Kotest](https://kotest.io/) · [MockK](https://mockk.io/) · [k6](https://grafana.com/docs/k6/latest/)

## 2. 이 저장소에서 해볼 확장 과제

난이도 순입니다. 각 과제는 이슈 → 브랜치 → PR 순서로 해 보세요. 이 저장소의 커밋·PR 기록이 좋은 예시입니다.

| 과제 | 배우는 것 | 건드릴 곳 |
|---|---|---|
| 대기열이 닫히면(소진·종료) SSE 구독자에게 바로 결과 보내기 | 이벤트 기반 알림, 폴링 주기와의 관계 | `CouponQueueStatusStreams`, `CouponQueueWorker` |
| 이벤트 일시 중지 | 상태 설계, 마이그레이션, **캐시 무효화** | `CouponEvent`, `StartedCouponEventCache`, `V5__...` |
| 쿠폰 발급 취소 | 수량 되돌리기, "소진은 되돌아가지 않는다" 가정 깨기 | `CouponIssueService`, `StartedCouponEventCache` |
| 회원별 요청 수 제한 (rate limit) | 토큰 버킷, Redis 로 서버 여러 대에서 함께 세기 | 필터 또는 `CouponIssueFacade`, Redisson `RRateLimiter` |
| k6 를 다른 기계에서 돌려 M5 를 다시 재기 | 측정 환경 분리, 흔들림을 줄이는 법 | `load-test/`, [13장](13-virtual-threads-and-backpressure.md) 6절 |
| 추첨(`LOTTERY`) 발급 방식 | enum 확장, sealed·when 의 힘 | `CouponIssueMode` 와 그 `when` 들 |

## 3. 코드 리뷰 연습 — 이 코드의 약점 찾기

좋은 코드에도 약점이 있습니다. 아래는 교재를 쓰면서 찾은 것들입니다. 먼저 해당 파일을 읽고 스스로 찾아본 뒤 답을 펼쳐 보세요.

1. [CouponController.kt](../src/main/kotlin/com/site/presentation/coupon/api/CouponController.kt) 의 `issue()` 주석
2. [JwtTokenProvider.parseAccessToken](../src/main/kotlin/com/site/infrastructure/security/jwt/JwtTokenProvider.kt) 이 서명은 맞지만 `role` 클레임이 없는 토큰을 받으면
3. [RedisAccessTokenBlacklist.add](../src/main/kotlin/com/site/infrastructure/redis/RedisAccessTokenBlacklist.kt) 의 현재 시각
4. [AuthService.reissue](../src/main/kotlin/com/site/domain/auth/service/AuthService.kt) 를 한 사용자가 두 탭에서 동시에 부르면

<details>
<summary>답</summary>

1. "즉시 발급 (분산락 + 사전 검사)" 는 M4 이전 설명입니다. 지금은 설정에 따라 원자적 UPDATE(기본) 또는 분산락입니다. 주석은 코드와 함께 늙습니다.
2. `claims.get(ROLE_CLAIM, String::class.java)` 가 null 을 돌려주고 `MemberRole.valueOf(null)` 이 `NullPointerException` 을 던집니다. 이것은 `JwtException` 이 아니라서 `catch` 에 걸리지 않고, 필터는 `ServiceException` 만 잡으므로 401 대신 500 이 납니다. 이 서버가 발급한 토큰에는 항상 role 이 있어 지금은 일어나지 않지만, 키가 같은 다른 발급자가 생기면 드러납니다.
3. `Instant.now()` 를 직접 씁니다. 다른 곳은 `Clock` 빈을 쓰는데 여기만 달라서, 테스트에서 시간을 통제할 수 없습니다.
4. 둘 다 같은 refresh token 을 보내면 하나는 `CONSUMED`, 다른 하나는 `REUSED` 가 되어 패밀리 전체가 폐기됩니다. 정상 사용자인데 로그아웃됩니다. Refresh Token Rotation 의 알려진 대가이고, 보통 "직전 토큰은 몇 초 동안 한 번 더 허용"하는 유예 시간으로 완화합니다.

</details>

## 4. 연습 문제 힌트

### 1장. Kotlin 기초

1. **`LOTTERY` 추가** — `CouponIssueMode.checkRequested` 의 `when (this)` 가 깨집니다. `else` 가 없어서 새 값을 어떻게 처리할지(어떤 `ErrorCode` 를 줄지) 정해야 합니다. 이것이 "else 를 쓰지 않는" 이유입니다.
2. **`resolveToken` 을 if 문으로** — 이런 모양이 됩니다(`// 예시`).
   ```kotlin
   // 예시
   val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
   if (!header.startsWith(BEARER_PREFIX)) return null
   val token = header.removePrefix(BEARER_PREFIX)
   return if (token.isNotBlank()) token else null
   ```
   줄 수는 비슷하지만, 체인 버전은 "단계마다 null 이면 멈춘다"가 한눈에 보입니다. 어느 쪽이 읽기 쉬운지는 팀의 합의입니다.
3. **`status` 의 순서** — 만료 확인을 먼저 하면, **사용한 뒤 만료 시각이 지난 쿠폰**이 `USED` 가 아니라 `EXPIRED` 로 보입니다. 내 쿠폰 목록에서 "사용함"이 "만료됨"으로 바뀌어 보이는 버그입니다.
4. **for 문으로** — `val results = mutableMapOf<Long, CouponQueueResult>()` 를 만들고 `issued`, `rejected` 를 각각 돌며 넣습니다. 바꿀 수 있는 컬렉션이 생기고 줄이 늘어납니다. 컬렉션 함수는 "무엇을 만드는지"만 남깁니다.

### 2장. 스프링이 Kotlin 을 만나면

1. **`plugin.spring` 제거** — 컨텍스트가 뜨지 못합니다. `@Configuration` 클래스는 final 일 수 없다는 에러나, `@Transactional` 프록시(CGLIB)를 만들 수 없다는 에러가 납니다. 메시지에 `final` 이 보이는지 확인해 보세요.
2. **`drainBatchSize` 설정** — `CouponQueueProperties` 에 `val drainBatchSize: Long = 1_000` 을 추가하고, 워커의 `DRAIN_BATCH_SIZE` 를 `properties.drainBatchSize` 로 바꿉니다. 메타데이터 JSON 에 `coupon.queue.drain-batch-size` 항목을 추가합니다.
3. **`distributed-lock` 으로 기동** — `CouponIssueFacade.issue` 의 `when (properties.directStrategy)` 가 `DISTRIBUTED_LOCK` 분기로 가서 `issueWithDistributedLock` 을 부릅니다. 바뀌는 곳이 이 `when` 한 곳뿐이라는 점이 핵심입니다.

### 3장. 프로젝트 구조와 계층

1. **ArchUnit 실패 메시지** — `RedisCouponQueueStore` 를 받으면 "domain 은 infrastructure 와 presentation 을 모른다" 규칙 위반으로 실패하고, 어느 클래스의 어느 필드·생성자가 문제인지 알려 줍니다. `StringRedisTemplate` 은 `com.site.infrastructure` 패키지가 아니라서 **통과합니다.** 규칙은 적어 둔 것만 지킵니다.
2. **`InMemoryCouponQueueStore`** — `ConcurrentHashMap` 하나하나는 안전하지만, "대기에서 꺼내 처리 중에 넣기"처럼 **여러 자료구조를 함께 바꾸는 연산**은 원자적이지 않습니다. 이벤트별 `synchronized` 블록(또는 `ReentrantLock`)으로 각 연산 전체를 감싸야 포트의 약속을 지킵니다. 서버가 여러 대면 메모리 구현은 성립하지 않는다는 점도 생각해 보세요.
3. **independent 정리** — `CustomSpringELParser` 는 `DistributedLockAspect`(infrastructure)에서만 씁니다. 옮겨도 되지만, "특정 기술에 묶이지 않은 범용 도구"라서 independent 에 둔 판단도 이해할 수 있습니다. 정답보다 기준을 정하는 것이 중요합니다.

### 4장. JPA 엔티티를 Kotlin 으로

1. **일시 중지** — 시간으로 계산할 수 없는 **사람의 결정**이므로 저장해야 합니다. 예: `paused BOOLEAN NOT NULL DEFAULT FALSE` 컬럼과 `V5__add_pause_to_coupon_event.sql`. 그리고 함정이 있습니다. 시작된 이벤트를 멈출 수 있게 되면 [7장](07-concurrency.md)의 `StartedCouponEventCache` 가 기대는 "시작 후에는 바뀌지 않는다"가 깨집니다.
2. **N+1 세기** — 쿠폰 페이지 조회 1번 + (페이지에 다음 페이지가 있으면) 개수 조회 1번 + 쿠폰마다 이벤트 조회 N번. 같은 이벤트를 여러 번 조회하면 1차 캐시가 일부를 줄여 주기도 합니다.
3. **`init` 에 "종료 > 지금"** — 끝난 이벤트를 만드는 테스트가 깨집니다. 예: [MyCouponApiTest](../src/test/kotlin/com/site/presentation/MyCouponApiTest.kt) 는 `endAt = serviceNow().minusDays(2)` 인 이벤트를 만듭니다. 객체 불변식은 "언제나 참"이어야 한다는 4장의 구분을 다시 보세요.

### 5장. 웹 계층과 예외 처리

1. **`@Email` 만 붙이면** — 잘못된 이메일로도 가입이 됩니다(201). 에러가 아니라 **아무 일도 일어나지 않는다**는 점이 위험합니다.
2. **이벤트 상세 조회** — `SuccessCode.GET_COUPON_EVENT_SUCCESS` 와 `ErrorCode.COUPON_EVENT_NOT_FOUND` 를 그대로 씁니다. 관리자용 응답(`CouponEventResponse`)을 재사용할지, 사용자에게 보이면 안 되는 필드가 있는지는 따로 판단해야 합니다.
3. **`Retry-After` 헤더** — `GlobalExceptionHandler.handleServiceException` 에서 `LOCK_ACQUISITION_FAILED` 일 때 헤더를 붙이거나, `ErrorCode` 에 재시도 간격 정보를 추가합니다. 후자는 모든 에러 코드에 영향을 주니 범위를 따져 보세요.

### 6장. 인증

1. **role 이 없는 토큰** — 3절 코드 리뷰 연습 2번의 답을 보세요. 기대 결과는 401(`MALFORMED_TOKEN` 또는 `INVALID_TOKEN`)이 맞습니다. 테스트를 먼저 쓰면 지금 코드가 500 을 낸다는 것이 드러납니다.
2. **동시 재발급 2개** — Lua 덕분에 정확히 하나는 `CONSUMED`, 하나는 `REUSED` 입니다. GET → DEL 로 따로 했다면, 둘 다 GET 에서 주인을 읽은 뒤 각자 DEL 해서 **둘 다 새 토큰을 받을** 수 있습니다. 재사용 감지가 뚫립니다.
3. **관리자별 담당 이벤트** — Role 은 "관리자인가"만 판단하고, "이 이벤트의 담당자인가"는 별도 데이터(예: `coupon_event_manager(coupon_event_id, member_id)` 테이블)로 서비스에서 확인합니다. 거친 권한(Role)과 세밀한 권한(소유)을 나누는 것이 일반적입니다.

### 7장. 동시성 제어

1. **`REQUIRED` 였다면** — 바깥 트랜잭션에 합류하므로 커밋은 바깥 메서드가 끝날 때입니다. 순서가 "락 → 쓰기 → **락 해제** → (바깥이 끝나면) 커밋"이 되어, 다음 요청이 커밋 전의 옛 수량을 읽습니다. 락을 걸었는데 Lost Update 가 납니다.
2. **수량 조건 삭제** — `원자적 UPDATE - 정확히 100장만 발급된다` 와 즉시 발급 입구의 `ATOMIC_UPDATE` 케이스가 깨집니다(초과 발급). 미리 읽은 값으로 하는 `checkIssuable` 은 동시에 들어온 요청을 막지 못합니다.
3. **발급 취소** — "소진은 되돌아가지 않는다"가 깨져서, 캐시의 소진 표시가 틀린 거절을 만듭니다. 서버마다 캐시가 따로 있으니 취소할 때 모든 서버에 알리거나(메시지 브로드캐스트), 소진 표시에 짧은 만료 시간을 두거나, 소진은 캐시하지 않는 방법이 있습니다. 각각 무엇을 잃는지 비교해 보세요.
4. **쿠폰 사용을 비관적 락으로** — 얻는 것: 충돌해도 예외 없이 기다렸다가 `USED` 를 보고 `ALREADY_USED_COUPON` 이 됩니다. 잃는 것: 충돌이 거의 없는데도 매번 행을 잠그고, 기다리는 동안 커넥션을 쥡니다.

### 8장. 비동기와 신뢰성

1. **`REQUIRES_NEW`** — Outbox 기록이 발급과 따로 먼저 커밋됩니다. 발급이 롤백돼도 Outbox 행이 남고, 그 트랜잭션의 커밋 이벤트로 알림까지 나갑니다. 테스트는 `outboxRepository.count()` 가 1 이 되어 실패합니다. `MANDATORY` 가 막으려던 바로 그 상황입니다.
2. **발송 3초 지연** — 발급 API 는 1초 안에 응답합니다. 알림은 커밋 후 다른 스레드에서 나가기 때문입니다. [NotificationOutboxTest](../src/test/kotlin/com/site/domain/notification/NotificationOutboxTest.kt) 의 "알림 발송이 느려도 발급 API 는 기다리지 않고 바로 응답한다" 가 2초 지연으로 이것을 확인합니다.
3. **lease 보다 긴 발송** — 30초가 지나면 스케줄러가 같은 건을 다시 선점해 **두 번 보냅니다.** 막는 방법: HTTP 클라이언트 타임아웃을 lease 보다 짧게, lease 를 넉넉하게, 외부 API 가 지원하면 멱등 키를 보냅니다.

### 9장. Redis 대기열

1. **워커가 죽은 상황 만들기** — `claimNext` 를 직접 부르면서 `leaseUntilMillis` 를 **이미 지난 시각**(`clock.millis() - 1`)으로 줍니다. "꺼냈는데 결과가 없고 lease 가 지난" 상태가 됩니다. 또 회원 1번은 `issueWithAtomicUpdate` 로 미리 발급해 "DB 발급은 끝났는데 Redis 결과 기록 전에 죽은" 상황을 만듭니다. 장애를 일으키지 않고 **장애가 남긴 흔적**을 만드는 방식입니다.
2. **남은 수량 얻기** — 워커는 배치마다 `couponEvent.remainingQuantity` 를 압니다. 그 값을 Redis 키(예: `coupon-queue:{id}:remaining`)에 써 두면 상태 조회가 DB 없이 읽을 수 있습니다. 첫 배치 전에는 값이 없으니, 시작 후 바뀌지 않는 총수량을 상한으로 쓰는 방법도 있습니다.
3. **마감 확인을 먼저** — 이미 쿠폰을 받은 회원이 마감 뒤 다시 줄을 서면, 자기 결과(`ISSUED`) 대신 `Closed` → `COUPON_SOLD_OUT` 에러를 받습니다. [CouponQueueTest](../src/test/kotlin/com/site/domain/coupon/queue/CouponQueueTest.kt) 의 "이미 처리된 사람은 다시 와도 자기 결과를 돌려받는다" 단언이 깨집니다. 스크립트의 확인 순서도 설계입니다.
4. **워커 두 대** — 수량은 지켜집니다. `claimNext` 가 원자적이라 한 회원을 두 워커가 가져가지 않고, `issueQueuedBatch` 의 행 잠금이 수량을 지킵니다. 순서는 느슨해집니다. 뒤 배치를 꺼낸 워커가 먼저 행 잠금을 잡으면, 소진 직전에 뒷사람이 앞사람보다 먼저 받을 수 있습니다.

### 10장. 테스트

1. **경계값 `withData`** — 이런 모양입니다(`// 예시`).
   ```kotlin
   // 예시
   context("종료 시각 경계") {
       withData(
           mapOf(
               "종료 1초 전 → 발급" to (endAt.minusSeconds(1) to null),
               "종료 시각 → ENDED" to (endAt to ErrorCode.COUPON_EVENT_ENDED),
               "종료 1초 후 → ENDED" to (endAt.plusSeconds(1) to ErrorCode.COUPON_EVENT_ENDED),
           ),
       ) { (now, expected) ->
           // expected 가 null 이면 발급 성공, 아니면 그 에러 코드
       }
   }
   ```
2. **격리 모드 지우기** — 기본값(`SingleInstance`)에서는 `Given` 에서 만든 객체를 모든 `Then` 이 공유합니다. 앞의 `Then` 이 객체를 바꾸면 다음 `Then` 이 바뀐 객체를 봅니다. 지금 테스트가 깨지지 않는다면, 그건 `Then` 들이 우연히 상태를 바꾸지 않기 때문입니다. 첫 `Then` 에서 `couponEvent.issue(startAt)` 을 부르고 두 번째 `Then` 에서 남은 수량이 2 라고 단언하는 테스트를 만들어 차이를 확인해 보세요.
3. **출발 신호 없애기** — 32개 스레드가 일을 받는 대로 시작하므로 여전히 겹치긴 하지만, 앞쪽 몇 개는 다른 작업이 제출되기 전에 끝나 버립니다. 여러 번 돌리면 결과가 들쭉날쭉해집니다. 동시성 테스트는 "가끔 실패"가 가장 나쁩니다. 그래서 겹침을 최대로 만드는 장치를 둡니다.

### 11장. 관측과 부하 테스트

1. **카운터에 이벤트 ID 태그** — 카운터 시계열은 서버가 재시작될 때까지 남습니다. 이벤트가 생길 때마다 시계열이 늘어 끝없이 쌓입니다. 대기열 크기 게이지는 `MultiGauge` 가 **지금 진행 중인 이벤트만** 덮어쓰고 끝난 이벤트는 지우므로 개수가 유지됩니다. "ID 를 태그로 써도 되나"는 동시에 살아 있는 개수로 판단합니다.
2. **Grafana 관찰** — 500명 즉시 발급에서 DB 커넥션 대기와 Tomcat 스레드가 언제 올라갔다 내려오는지 보세요. 실행이 1~2초 만에 끝나서 1초 간격 표본으로는 최대치를 놓칠 수 있다는 점도 확인해 보세요.
3. **즉시 발급 vs 대기열** — 즉시 발급이 빠른 예: 1,000장 · 5,000명에서 2.27s vs 5.59s (대기열은 줄 서기 몰림 + 조회 간격만큼 결과를 늦게 안다). 비슷한 예: 1,000장 · 2,000명에서 1.92s vs 1.86s. 그러나 즉시 발급은 그동안 DB 커넥션 대기가 110 까지 쌓였고 대기열은 0 이었습니다. 빠르기만 보지 말고 **무엇을 대가로 냈는지** 같이 말하는 연습을 하세요.

## 마치며

이 교재의 코드는 처음부터 이 모습이 아니었습니다. 락 없는 발급에서 시작해 초과 발급을 테스트로 재현하고, 네 가지 방식을 비교하고, 알림 유실을 Outbox 로 막고, 부하 테스트에서 찾은 병목을 하나씩 고쳤습니다. 그 과정이 `git log` 와 PR 에 남아 있습니다.

다음 기능을 만들 때도 같은 순서를 따라 보세요.

1. 문제를 테스트나 측정으로 **재현**한다.
2. 방법을 둘 이상 놓고 **대가**를 비교한다.
3. 고른 이유를 코드 주석과 PR 에 **남긴다**.
4. 고친 뒤 같은 조건으로 **다시 잰다**.

처음으로: [교재 목차](README.md)
