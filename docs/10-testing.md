# 10장. 테스트 — 시간·동시성·외부 API 를 통제하기

**한 줄 요약**: 규칙은 스프링 없이 빠르게(Kotest + MockK), 협력은 운영과 같은 MySQL·Redis 로(Testcontainers) 확인합니다. 시간은 `Clock` 으로, 동시성은 "한 번에 출발"로, 외부 API 는 가짜 구현으로 통제합니다.

## 1. 두 종류의 테스트

| | 단위 테스트 | 통합 테스트 |
|---|---|---|
| 무엇을 | 엔티티 규칙, 서비스의 분기, 정책 계산 | API → DB·Redis 까지, 동시성, 트랜잭션 |
| 스프링 | 띄우지 않는다 | `@SpringBootTest` |
| 의존성 | MockK 로 가짜 | Testcontainers 로 진짜 MySQL·Redis |
| 스타일 | Kotest (`BehaviorSpec`, `FunSpec`) | JUnit 5 (`@Test`) |
| 속도 | 밀리초 | 컨텍스트 기동 수 초 (한 번만) |
| 예 | [CouponEventTest](../src/test/kotlin/com/site/domain/coupon/entity/CouponEventTest.kt), [StartedCouponEventCacheTest](../src/test/kotlin/com/site/domain/coupon/service/StartedCouponEventCacheTest.kt) | [CouponIssueConcurrencyTest](../src/test/kotlin/com/site/domain/coupon/service/CouponIssueConcurrencyTest.kt), [CouponQueueApiTest](../src/test/kotlin/com/site/presentation/CouponQueueApiTest.kt) |

```bash
./gradlew test                                          # 전부
./gradlew test --tests '*CouponEventTest*'              # 하나만
./gradlew test --tests '*CouponIssueConcurrencyTest*'   # 동시성 비교 (로그에 "동시 발급 결과")
```

## 2. Kotest — 테스트를 문장처럼

### BehaviorSpec — Given / When / Then

[CouponEventTest.kt](../src/test/kotlin/com/site/domain/coupon/entity/CouponEventTest.kt#L11-L42)

```kotlin
/**
 * BehaviorSpec: Given(상황) / When(행동) / Then(결과) 로 비즈니스 규칙을 문장처럼 적는다.
 * 엔티티가 시각을 인자로 받기 때문에 스프링 없이 원하는 시각을 넣어 검증할 수 있다.
 */
class CouponEventTest : BehaviorSpec({

    val startAt = LocalDateTime.of(2026, 11, 1, 10, 0)
    val endAt = startAt.plusDays(7)

    fun couponEvent(totalQuantity: Int = 100, issueMode: CouponIssueMode = CouponIssueMode.DIRECT) =
        CouponEvent(
            name = "신규 가입 할인",
            discount = Discount(DiscountType.FIXED_AMOUNT, 5_000),
            period = EventPeriod(startAt, endAt),
            totalQuantity = totalQuantity,
            issueMode = issueMode,
        )

    Given("11월 1일 10시부터 일주일 동안 열리는 이벤트") {
        val couponEvent = couponEvent()

        Then("시작 1초 전에는 UPCOMING") {
            couponEvent.status(startAt.minusSeconds(1)) shouldBe CouponEventStatus.UPCOMING
        }
        Then("시작 시각부터 종료 1초 전까지는 ONGOING") {
            couponEvent.status(startAt) shouldBe CouponEventStatus.ONGOING
            couponEvent.status(endAt.minusSeconds(1)) shouldBe CouponEventStatus.ONGOING
        }
        Then("종료 시각부터는 ENDED") {
            couponEvent.status(endAt) shouldBe CouponEventStatus.ENDED
        }
    }
```

- `class X : BehaviorSpec({ ... })`: 클래스 본문 대신 생성자에 람다를 넘깁니다. 람다 안이 테스트 정의입니다.
- `a shouldBe b`: 단언(assert). `infix` 함수라 연산자처럼 씁니다.
- 경계값(시작 1초 전, 시작 시각, 종료 시각)을 문장으로 적어서, 테스트 보고서가 그대로 명세가 됩니다.
- 이 테스트가 스프링 없이 가능한 이유는 [4장](04-jpa-with-kotlin.md)에서 엔티티가 `now` 를 인자로 받게 했기 때문입니다.

예외 단언:

```kotlin
            shouldThrow<ServiceException> { couponEvent.issue(startAt) }.errorCode shouldBe ErrorCode.COUPON_SOLD_OUT
```

### FunSpec + withData — 표로 쓰는 테스트

입력과 기대값이 표가 되는 테스트는 `withData` 로 씁니다.

[DiscountTest.kt](../src/test/kotlin/com/site/domain/coupon/entity/DiscountTest.kt#L11-L34)

```kotlin
/**
 * 입력과 기대값이 표로 정리되는 테스트는 withData 로 쓴다. 행마다 별도의 테스트로 실행되고 이름이 붙는다.
 */
class DiscountTest : FunSpec({

    context("할인 값은 방식별 허용 범위 안이어야 한다") {
        withData(
            mapOf(
                "정액 1원" to Triple(DiscountType.FIXED_AMOUNT, 1, true),
                "정액 1,000,000원" to Triple(DiscountType.FIXED_AMOUNT, 1_000_000, true),
                "정액 0원" to Triple(DiscountType.FIXED_AMOUNT, 0, false),
                "정액 1,000,001원" to Triple(DiscountType.FIXED_AMOUNT, 1_000_001, false),
                "정률 1%" to Triple(DiscountType.PERCENTAGE, 1, true),
                "정률 100%" to Triple(DiscountType.PERCENTAGE, 100, true),
                "정률 101%" to Triple(DiscountType.PERCENTAGE, 101, false),
            ),
        ) { (type, value, valid) ->
            if (valid) {
                shouldNotThrowAny { Discount(type, value) }
            } else {
                shouldThrow<ServiceException> { Discount(type, value) }.errorCode shouldBe ErrorCode.INVALID_DISCOUNT_VALUE
            }
        }
    }
```

- `"이름" to 값` 은 `Pair` 를 만드는 infix 함수입니다. `mapOf` 의 키가 테스트 이름이 됩니다.
- `{ (type, value, valid) -> ... }` 는 `Triple` 을 구조 분해합니다.
- 7줄이 7개의 테스트로 따로 실행되어, 어느 행이 실패했는지 바로 보입니다.

### 격리 — 테스트마다 새 인스턴스

[ProjectConfig.kt](../src/test/kotlin/io/kotest/provided/ProjectConfig.kt)

```kotlin
/**
 * Kotest 전역 설정. 이 패키지·이름(io.kotest.provided.ProjectConfig)이면 Kotest 가 자동으로 찾는다.
 *
 * 기본값(SingleInstance)은 스펙 인스턴스 하나를 모든 테스트가 공유해서, Given 블록에서 만든 객체를
 * 한 Then 이 바꾸면 다음 Then 이 바뀐 객체를 보게 된다. 테스트(leaf)마다 새 인스턴스로 실행해 서로 섞이지 않게 한다.
 */
object ProjectConfig : AbstractProjectConfig() {
    override val isolationMode = IsolationMode.InstancePerLeaf
}
```

`InstancePerLeaf` 덕분에 각 `Then`(가장 안쪽 블록)이 실행될 때마다 바깥 블록이 처음부터 다시 실행됩니다. 앞의 `Then` 이 `couponEvent.issue()` 를 불러도 다음 `Then` 은 새 객체를 봅니다.

## 3. MockK — 가짜 의존성

[MemberServiceTest.kt](../src/test/kotlin/com/site/domain/member/service/MemberServiceTest.kt#L17-L50)

```kotlin
/** MockK 로 리포지토리를 대신한다. 스펙은 테스트(leaf)마다 새로 만들어지므로 목도 매번 새것이다. (ProjectConfig) */
class MemberServiceTest : BehaviorSpec({

    val memberRepository = mockk<MemberRepository>()
    val passwordEncoder = mockk<PasswordEncoder>()
    val memberService = MemberService(memberRepository, passwordEncoder)

    every { passwordEncoder.encode("password123") } returns "{bcrypt}encoded"

    fun signUp() = memberService.signUp(email = "user@test.com", rawPassword = "password123", nickname = "tester")

    Given("처음 쓰는 이메일") {
        every { memberRepository.existsByEmail("user@test.com") } returns false

        When("가입하면") {
            val saved = slot<Member>()
            every { memberRepository.saveAndFlush(capture(saved)) } answers { saved.captured }

            signUp()

            Then("비밀번호를 인코딩해서 저장한다") {
                saved.captured.password shouldBe "{bcrypt}encoded"
                saved.captured.email shouldBe "user@test.com"
            }
        }

        When("중복 검사는 통과했지만 동시에 같은 이메일로 가입한 요청이 먼저 저장되면") {
            every { memberRepository.saveAndFlush(any()) } throws DataIntegrityViolationException("Duplicate entry")

            Then("unique 제약 위반을 DUPLICATE_EMAIL 로 바꿔 알려준다") {
                shouldThrow<ServiceException> { signUp() }.errorCode shouldBe ErrorCode.DUPLICATE_EMAIL
            }
        }
    }
```

| MockK | 뜻 |
|---|---|
| `mockk<T>()` | T 의 가짜. 정해 주지 않은 호출은 예외 |
| `every { 호출 } returns 값` | 이 호출이 오면 이 값을 돌려준다 |
| `every { ... } answers { ... }` | 돌려줄 값을 그때 계산 |
| `every { ... } throws 예외` | 예외를 던진다 |
| `slot<T>()` + `capture(slot)` | 넘어온 인자를 잡아 둔다 |
| `verify(exactly = n) { 호출 }` | 정확히 n 번 불렸는지 |

생성자 주입([2장](02-spring-meets-kotlin.md)) 덕분에 `MemberService(가짜, 가짜)` 로 바로 만듭니다. 스프링이 필요 없습니다.

"동시에 같은 이메일로 가입" 같은 경쟁 상황도, DB 가 던질 예외를 흉내 내면 단위 테스트로 확인할 수 있습니다.

### 확장 함수는 모킹하기 번거롭다

`findByIdOrNull` 은 Spring Data 의 확장 함수입니다. 확장 함수는 사실 정적 함수라 `every { repo.findByIdOrNull(1L) }` 로 잡기 어렵습니다. 대신 그 안에서 부르는 `findById` 를 모킹합니다.

[StartedCouponEventCacheTest.kt](../src/test/kotlin/com/site/domain/coupon/service/StartedCouponEventCacheTest.kt#L22-L49)

```kotlin
class StartedCouponEventCacheTest : FunSpec({

    val start = LocalDateTime.of(2026, 10, 26, 12, 0)
    var now = start.plusMinutes(1)
    val clock = mockk<Clock> {
        every { zone } returns ClockConfig.SERVICE_ZONE
        every { instant() } answers { now.atZone(ClockConfig.SERVICE_ZONE).toInstant() }
    }
    val couponEventRepository = mockk<CouponEventRepository>()
    val cache = StartedCouponEventCache(couponEventRepository, clock)

    fun givenEvent(couponEvent: CouponEvent) {
        // findByIdOrNull 은 확장 함수라 내부에서 부르는 findById 를 모킹한다
        every { couponEventRepository.findById(1L) } returns Optional.of(couponEvent)
    }

    fun event(totalQuantity: Int = 100, issueMode: CouponIssueMode = CouponIssueMode.DIRECT) =
        couponEventFixture(startAt = start, endAt = start.plusHours(1), totalQuantity = totalQuantity, issueMode = issueMode)

    fun errorCodeOf(mode: CouponIssueMode = CouponIssueMode.DIRECT) = shouldThrow<ServiceException> { cache.checkIssuable(1L, mode) }.errorCode

    test("진행 중인 이벤트는 처음 한 번만 DB 에서 읽는다") {
        givenEvent(event())

        repeat(3) { cache.checkIssuable(1L, CouponIssueMode.DIRECT) }

        verify(exactly = 1) { couponEventRepository.findById(1L) }
    }
```

이 테스트에는 요령이 두 가지 더 있습니다.

- **움직이는 시계**: `Clock` 을 MockK 로 만들고 `instant()` 가 바깥 변수 `now` 를 읽게 했습니다. 테스트 중에 `now = start.plusHours(1)` 로 바꾸면 "시간이 흐른" 상황이 됩니다. `answers { }` 는 호출될 때마다 블록을 다시 실행하므로 바뀐 값을 읽습니다.
- **"DB 를 안 읽는다"를 숫자로**: 캐시의 목적은 DB 를 덜 읽는 것이므로, `verify(exactly = 1)` 로 리포지토리 호출 횟수를 셉니다.

## 4. 통합 테스트 — 운영과 같은 DB 로

### Testcontainers

[TestContainersConfig.kt](../src/test/kotlin/com/site/support/TestContainersConfig.kt#L10-L30)

```kotlin
/**
 * 테스트용 MySQL / Redis 컨테이너를 스프링 빈으로 띄운다.
 * @ServiceConnection 이 컨테이너의 접속 정보를 DataSource / Redis 설정으로 등록해주므로 properties 에 적을 필요가 없다.
 * (Redisson 도 이 접속 정보를 그대로 읽는다)
 *
 * H2 대신 운영과 같은 MySQL 을 쓰는 이유: 락·격리 수준 동작과 DDL(컬럼 타입)이 MySQL 과 달라서
 * 동시성 테스트나 스키마 문제를 H2 로는 제대로 검증할 수 없다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> = MySQLContainer(DockerImageName.parse("mysql:8.4"))

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
}
```

테스트가 시작되면 Docker 로 MySQL 8.4 와 Redis 7.4 를 띄우고, 접속 정보를 스프링에 연결합니다. 테스트 DB 는 처음에 H2(메모리 DB)였다가 이것으로 바꿨습니다(커밋 `1952c61`). 이 프로젝트의 핵심이 행 잠금·격리 수준인데, H2 는 MySQL 과 다르게 동작하기 때문입니다.

### 메타 애너테이션 하나로

[IntegrationTest.kt](../src/test/kotlin/com/site/support/IntegrationTest.kt#L9-L21)

```kotlin
/**
 * 통합 테스트용 메타 애너테이션.
 * 모든 통합 테스트가 같은 설정 조합을 쓰게 해서 스프링 컨텍스트(와 Redis 컨테이너)를 한 번만 띄우고 재사용한다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
// 테스트는 기본적으로 지표 내보내기를 끈다. 운영과 같은 Prometheus 레지스트리를 쓰도록 켠다
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
@Import(TestContainersConfig::class, TestAuthHelper::class, FakeAlimtalkSender::class, LearningCouponIssueStrategies::class)
annotation class IntegrationTest
```

스프링은 **설정 조합이 같은 테스트끼리 컨텍스트를 재사용**합니다. 테스트 클래스마다 애너테이션을 조금씩 다르게 붙이면 컨텍스트(와 컨테이너)를 여러 번 띄워 느려집니다. 메타 애너테이션 하나로 조합을 고정했습니다.

`@Import` 한 도우미들:

| 도우미 | 하는 일 |
|---|---|
| `TestAuthHelper` | 회원을 만들고 로그인해서 access token 을 돌려준다 |
| `FakeAlimtalkSender` | `@Primary` 로 진짜 발송기를 대신한다. 받은 메시지를 기록하고, 지연·실패를 흉내 낸다 |
| `LearningCouponIssueStrategies` | 운영에서 뺀 "락 없음"·"비관적 락" 구현 ([7장](07-concurrency.md)) |

이들은 `@Component` 가 아니라 `@TestComponent` 입니다. ([TestAuthHelper.kt](../src/test/kotlin/com/site/support/TestAuthHelper.kt) 주석: "src/test 아래의 일반 @Component 는 com.site 패키지 스캔에 걸려 의도치 않게 등록될 수 있다")

### 스케줄러는 끄고 직접 부른다

[application-test.properties](../src/test/resources/application-test.properties)

```properties
# 알림 Outbox: 재시도를 바로 하고, 스케줄러는 꺼서 테스트가 직접 처리 시점을 정한다
notification.outbox.backoff=0s
notification.outbox.max-attempts=3
notification.outbox.scheduler.enabled=false

# 대기열 워커는 테스트에서 직접 호출한다 (스케줄러가 몰래 돌면 결과를 예측할 수 없다)
coupon.queue.scheduler.enabled=false
```

스케줄러가 200ms 마다 몰래 돌면, 테스트가 "워커가 돌기 전" 상태를 확인하려는 순간 이미 처리돼 있을 수 있습니다. 끄고 `couponQueueWorker.processAll()` 을 원하는 시점에 직접 부릅니다. 이것이 [2장](02-spring-meets-kotlin.md)에서 `@ConditionalOnProperty` 를 스케줄러에 붙인 이유입니다.

### 테스트끼리 섞이지 않게

컨텍스트를 공유하므로 DB·Redis 도 공유합니다. 각 테스트가 끝나면 지웁니다.

```kotlin
// CouponQueueTest.kt
    @AfterEach
    fun tearDown() {
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        redisTemplate.flushDb()
    }
```

## 5. 동시성 테스트 — 한 번에 출발시키기

스레드 300개를 그냥 띄우면 앞의 스레드가 끝난 뒤에 뒤의 스레드가 시작해서 겹치지 않을 수 있습니다. **모두 출발선에 세웠다가 동시에 출발**시킵니다.

[CouponIssueConcurrencyTest.kt](../src/test/kotlin/com/site/domain/coupon/service/CouponIssueConcurrencyTest.kt#L237-L276)

```kotlin
    /**
     * 요청 requestCount 개를 스레드 THREAD_COUNT 개로 동시에 보낸다.
     * startSignal 로 모든 작업을 대기시켰다가 한 번에 출발시켜서, 요청이 최대한 겹치게 만든다.
     *
     * issue 파라미터에는 learningStrategies::issueWithoutLock 같은 "함수 참조"를 넘긴다.
     */
    private fun issueConcurrently(
        label: String,
        issue: (Long, Long) -> IssuedCoupon,
        requestCount: Int = REQUEST_COUNT,
        memberIdOf: (Int) -> Long = { index -> index + 1L },
    ): IssueResult {
        val couponEventId = couponEventRepository.save(couponEventFixture(totalQuantity = TOTAL_QUANTITY)).id

        val executor = Executors.newFixedThreadPool(THREAD_COUNT)
        val startSignal = CountDownLatch(1)
        val doneSignal = CountDownLatch(requestCount)
        val successCount = AtomicInteger()
        val failReasons = ConcurrentHashMap<String, AtomicInteger>()

        repeat(requestCount) { index ->
            executor.execute {
                try {
                    startSignal.await()
                    issue(couponEventId, memberIdOf(index))
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    val reason = (e as? ServiceException)?.errorCode?.name ?: e.javaClass.simpleName
                    failReasons.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
                } finally {
                    doneSignal.countDown()
                }
            }
        }

        val startedAt = System.nanoTime()
        startSignal.countDown()
        check(doneSignal.await(1, TimeUnit.MINUTES)) { "동시 요청이 1분 안에 끝나지 않았습니다" }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        executor.shutdown()
```

| 도구 | 역할 |
|---|---|
| `startSignal = CountDownLatch(1)` | 출발 신호. 모든 작업이 `await()` 로 대기 |
| `startSignal.countDown()` | 동시에 출발 |
| `doneSignal = CountDownLatch(requestCount)` | 모두 끝날 때까지 기다리기 (최대 1분) |
| `AtomicInteger`, `ConcurrentHashMap` | 여러 스레드가 동시에 세도 안전한 카운터 |
| `issue: (Long, Long) -> IssuedCoupon` | 함수 타입 파라미터. 방식만 바꿔 같은 조건으로 비교 |

단언은 **불변식**으로 합니다. "정확히 몇 ms 안에"가 아니라 "발급 수 = 수량 컬럼 = 쿠폰 행 수 = 100, 실패는 전부 소진" 입니다.

```kotlin
    private fun assertExactlyTotalIssued(result: IssueResult) {
        assertEquals(TOTAL_QUANTITY, result.successCount)
        assertEquals(TOTAL_QUANTITY, result.issuedQuantity)
        assertEquals(TOTAL_QUANTITY.toLong(), result.issuedCouponCount)
        // 실패는 전부 "소진" 때문이어야 한다. (데드락·락 타임아웃 같은 예외가 섞이면 안 된다)
        assertEquals(mapOf("COUPON_SOLD_OUT" to REQUEST_COUNT - TOTAL_QUANTITY), result.failReasons)
```

마지막 줄이 중요합니다. 수량만 맞으면 통과시키지 않고, **실패 이유까지** 확인합니다. 데드락이나 락 타임아웃이 섞여도 수량은 맞을 수 있기 때문입니다.

같은 방식의 도우미가 공용으로도 있습니다. ([TestSupport.kt](../src/test/kotlin/com/site/support/TestSupport.kt) 의 `runConcurrently`)

## 6. 비동기 결과 기다리기 — Awaitility

알림은 다른 스레드에서 나갑니다. 발급 직후에 확인하면 아직 안 나갔을 수 있습니다. `Thread.sleep(1000)` 은 느리고 불안정합니다. **조건이 맞을 때까지 기다립니다.**

```kotlin
// NotificationOutboxTest.kt
        await atMost Duration.ofSeconds(5) untilAsserted {
            assertEquals(OutboxStatus.SENT, outboxRepository.findAll().single().status)
        }
```

"일어나지 않아야 하는 것"은 일정 시간 동안 계속 확인합니다.

```kotlin
        await.during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted {
            assertEquals(0, fakeAlimtalkSender.attempts.get())
        }
```

## 7. API 와 SSE 테스트 — MockMvc

[CouponQueueApiTest.kt](../src/test/kotlin/com/site/presentation/CouponQueueApiTest.kt#L99-L118)

```kotlin
    @Test
    fun `SSE 로 연결하면 지금 상태를 보내고, 워커가 발급하면 결과를 보낸 뒤 연결을 닫는다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.QUEUE)).id
        mockMvc.post("/api/coupon-events/$couponEventId/queue") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }

        val stream = mockMvc.get("/api/coupon-events/$couponEventId/queue/me/stream") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect { request { asyncStarted() } }.andReturn()
        assertTrue(stream.response.contentAsString.contains("\"state\":\"WAITING\"")) { stream.response.contentAsString }

        couponQueueWorker.processAll()
        queueStreams.pushAll() // 스케줄러 대신 직접 부른다

        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk)
        val events = stream.response.contentAsString
        assertTrue(events.contains("event:status")) { events }
        assertTrue(events.contains("\"state\":\"DONE\"") && events.contains("\"result\":\"ISSUED\"")) { events }
        assertEquals(0, queueStreams.connectionCount())
    }
```

- `mockMvc.post(...) { ... }`, `andExpect { status { isOk() } }` 는 Spring 의 MockMvc Kotlin DSL 입니다.
- 실제 서버를 띄우지 않고 디스패처를 직접 부르므로 빠릅니다. 대신 비동기 응답은 `asyncDispatch` 로 마무리를 직접 시킵니다.
- MockMvc 는 실제 서블릿 컨테이너가 아니라서, 연결 유지·종료 같은 동작은 실제 서버에서 한 번 더 보는 것이 안전합니다. SSE 를 추가할 때(PR #42) 실제 Tomcat 에서도 확인했습니다. 3,000명 뒤에 선 회원이 연결 하나로 순번 2,901 → … → 1 을 받은 뒤 `DONE` 을 받았습니다.

## 8. 그 밖의 안전망

| 테스트 | 무엇을 지키나 |
|---|---|
| [LayerDependencyTest](../src/test/kotlin/com/site/architecture/LayerDependencyTest.kt) | 계층 의존 방향 ([3장](03-architecture.md)) |
| [ServiceTimeZoneTest](../src/test/kotlin/com/site/independent/config/ServiceTimeZoneTest.kt) | 테스트 JVM 은 UTC, 서비스 Clock 은 Asia/Seoul |
| [ProdEnvironmentValidatorTest](../src/test/kotlin/com/site/independent/config/ProdEnvironmentValidatorTest.kt) | 운영 환경변수 누락 시 기동 실패 |
| [ObservabilityTest](../src/test/kotlin/com/site/domain/coupon/ObservabilityTest.kt) | 지표가 Prometheus 경로로 나간다 ([11장](11-observability-and-load-test.md)) |
| Flyway + `ddl-auto=validate` | 엔티티와 마이그레이션이 어긋나면 컨텍스트 기동 실패 |

CI([.github/workflows/ci.yml](../.github/workflows/ci.yml))가 모든 PR 에서 `ktlintCheck` 와 `test` 를 돌립니다. GitHub Actions 러너의 Docker 로 Testcontainers 가 돌아갑니다.

## 흔한 실수

| 실수 | 결과 | 대신 |
|---|---|---|
| 테스트에서 `LocalDateTime.now()` | 테스트 JVM(UTC)과 서비스 시간대가 달라 9시간 어긋난다 | `serviceNow()` (TestSupport) 또는 고정 시각 |
| 통합 테스트마다 다른 애너테이션 조합 | 컨텍스트를 여러 번 띄워 느려진다 | 메타 애너테이션 |
| 스케줄러를 켜 둔다 | 결과가 실행마다 달라진다 | 끄고 직접 호출 |
| `Thread.sleep` 으로 비동기 대기 | 느리고 가끔 실패한다 | Awaitility |
| 동시성 테스트를 스레드만 많이 띄워서 | 실제로는 겹치지 않는다 | `CountDownLatch` 로 동시 출발 |
| 동시성 테스트에서 수량만 확인 | 데드락·타임아웃이 섞여도 통과 | 실패 이유까지 확인 |
| H2 로 동시성 테스트 | 운영(MySQL)과 잠금 동작이 다르다 | Testcontainers |

## 직접 해보기

1. `CouponEventTest` 에 "정확히 종료 시각에 발급하면 COUPON_EVENT_ENDED" 를 `withData` 로 경계값 세 개(종료 1초 전, 종료 시각, 종료 1초 후)를 묶어 써 보세요.
2. `ProjectConfig` 의 `isolationMode` 를 지우면(기본값 `SingleInstance`) `CouponEventTest` 의 "2장짜리 진행 중 이벤트" 블록은 어떻게 되나요? 왜 그런가요?
3. `CouponIssueConcurrencyTest` 에서 `startSignal` 을 없애면(각 스레드가 바로 시작) "락 없음" 테스트는 여전히 정합성이 깨질까요? 몇 번 돌려 보세요.

다음 장: [11장. 관측과 부하 테스트](11-observability-and-load-test.md)
