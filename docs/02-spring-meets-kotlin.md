# 2장. 스프링이 Kotlin 을 만나면

**한 줄 요약**: Spring 과 JPA 는 Java 를 기준으로 만들어졌습니다. Kotlin 의 "기본이 final", "기본 생성자 없음"이 이들과 부딪히는 지점을 Gradle 플러그인 두 개가 메워 줍니다.

## 1. 스프링이 하는 일, 아주 짧게

Spring 은 객체를 대신 만들고 서로 연결해 주는 **컨테이너**입니다.

- `@Service`, `@Component`, `@Repository`, `@RestController` 가 붙은 클래스를 찾아 객체(빈, bean)로 만든다.
- 생성자에 필요한 다른 빈을 찾아 넣어 준다. (의존성 주입)
- `@Transactional`, `@Async`, `@DistributedLock` 같은 애너테이션이 붙은 메서드 앞뒤에 기능을 끼워 넣는다. 이때 **원래 클래스를 상속한 프록시 객체**를 만든다.

마지막 줄이 Kotlin 과 부딪히는 첫 번째 지점입니다.

## 2. 문제 1 — Kotlin 클래스는 기본이 `final`

Kotlin 클래스와 메서드는 `open` 을 붙이지 않으면 상속할 수 없습니다. 그런데 Spring 은 `@Transactional` 을 처리하려고 클래스를 상속한 프록시(CGLIB)를 만듭니다. 상속이 막혀 있으면 프록시를 만들 수 없습니다.

JPA 도 마찬가지입니다. 연관 엔티티를 늦게 불러오는(lazy loading) 프록시가 엔티티를 상속합니다.

해결은 플러그인이 대신 `open` 을 붙여 주는 것입니다.

[build.gradle.kts](../build.gradle.kts#L1-L15)

```kotlin
plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    kotlin("plugin.jpa") version "2.1.10"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "6.0.1.5171"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

| 설정 | 하는 일 |
|---|---|
| `kotlin("plugin.spring")` | `@Component`(와 그것을 품은 `@Service`, `@Configuration` 등), `@Transactional`, `@Async` 가 붙은 클래스를 자동으로 `open` 으로 만든다 |
| `allOpen { annotation(...) }` | JPA 엔티티·임베디드 타입도 `open` 으로 만든다 (지연 로딩 프록시용) |
| `kotlin("plugin.jpa")` | 엔티티에 **인자 없는 생성자**를 만들어 준다 (다음 절) |

그래서 이 프로젝트 코드 어디에도 `open class` 가 없습니다.

## 3. 문제 2 — JPA 는 인자 없는 생성자가 필요하다

JPA 는 DB 에서 행을 읽어 올 때 **빈 객체를 먼저 만들고 필드를 채웁니다.** 그러려면 인자 없는 생성자가 있어야 합니다. Kotlin 클래스는 주 생성자에 파라미터가 있으면 인자 없는 생성자가 없습니다.

`kotlin("plugin.jpa")` 가 코드에서는 보이지 않는 인자 없는 생성자를 만들어 줍니다. 여기에 재미있는 결과가 하나 따라옵니다.

[Discount.kt](../src/main/kotlin/com/site/domain/coupon/entity/Discount.kt#L12-L32)

```kotlin
/**
 * 할인 정책 값 객체. coupon_event 테이블의 컬럼 두 개(discount_type, discount_value)로 저장된다.
 *
 * init 블록은 우리가 생성자를 호출할 때만 실행된다. (JPA 가 DB 에서 읽어 올 때 쓰는 기본 생성자는
 * kotlin("plugin.jpa") 가 만든 것이라 init 을 거치지 않는다) 그래서 "잘못된 값으로는 만들 수조차 없는" 객체가 된다.
 */
@Embeddable
class Discount(
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "discount_type", nullable = false, length = 20)
    val type: DiscountType,

    @Column(name = "discount_value", nullable = false)
    val value: Int,
) {
    init {
        if (!type.isValidValue(value)) {
            throw ServiceException(ErrorCode.INVALID_DISCOUNT_VALUE)
        }
    }
```

- 코드에서 `Discount(...)` 를 부르면 `init` 검사를 거친다 → 잘못된 할인은 만들 수 없다.
- JPA 가 DB 에서 읽을 때는 `init` 을 거치지 않는다 → 이미 저장된 데이터 때문에 조회가 깨지지 않는다.

## 4. 생성자 주입 — `@Autowired` 가 없는 이유

[CouponEventService.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponEventService.kt#L15-L20)

```kotlin
@Service
@Transactional(readOnly = true)
class CouponEventService(
    private val couponEventRepository: CouponEventRepository,
    private val clock: Clock,
) {
```

- 주 생성자의 `private val` 파라미터가 곧 필드입니다.
- 생성자가 하나뿐이면 Spring 은 `@Autowired` 없이 그 생성자로 주입합니다.
- 필드가 `val` 이라 주입된 뒤 바뀌지 않고, 테스트에서는 `CouponEventService(가짜리포지토리, 고정Clock)` 처럼 그냥 생성자를 부르면 됩니다.

테스트 클래스는 예외입니다. JUnit 이 테스트 객체를 만들기 때문에 생성자에 `@Autowired` 를 명시합니다.

```kotlin
// CouponIssueConcurrencyTest.kt
@IntegrationTest
class CouponIssueConcurrencyTest @Autowired constructor(
    private val couponIssueService: CouponIssueService,
```

## 5. 설정값을 타입으로 — `@ConfigurationProperties` + `data class`

`application.properties` 의 값을 불변 객체로 받습니다.

[CouponQueueProperties.kt](../src/main/kotlin/com/site/domain/coupon/queue/CouponQueueProperties.kt)

```kotlin
/**
 * @property batchSize 워커가 한 번에 대기열에서 꺼내 발급하는 인원. (주기 × 배치 크기 = 초당 처리량의 상한)
 * @property keyTtl 대기열 관련 Redis 키의 만료 시간. 이벤트가 끝난 뒤 키가 영원히 남지 않게 한다.
 * @property processingLease 꺼낸 회원을 처리 중으로 잡아 두는 시간. 이 시간이 지나도록 결과가 없으면
 *   처리하던 워커가 죽은 것으로 보고 다음 주기에 다시 처리한다. 한 배치 처리 시간보다 넉넉해야 한다.
 * @property maxBusyTime 줄이 남아 있으면 쉬지 않고 다음 배치를 이어서 처리하되, 한 이벤트에 이 시간까지만 쓴다.
 *   너무 길면 다른 이벤트의 대기열이나 같은 스케줄러를 쓰는 작업이 밀린다.
 */
@ConfigurationProperties(prefix = "coupon.queue")
data class CouponQueueProperties(
    val batchSize: Long = 50,
    val keyTtl: Duration = Duration.ofDays(1),
    val processingLease: Duration = Duration.ofSeconds(30),
    val maxBusyTime: Duration = Duration.ofSeconds(1),
)
```

- 생성자 파라미터의 **기본값이 곧 설정 기본값**입니다. properties 에 없으면 기본값을 씁니다.
- `Duration` 은 `30s`, `1d`, `300ms` 같은 문자열을 Spring 이 알아서 바꿔 줍니다. ([JwtProperties.kt](../src/main/kotlin/com/site/infrastructure/security/jwt/JwtProperties.kt) 주석: `jwt.access-token-expiration=30m → Duration.ofMinutes(30)`)
- 이름은 케밥 표기로 씁니다. `coupon.queue.batch-size=100` → `batchSize`
- enum 도 됩니다. `coupon.issue.direct-strategy=distributed-lock` → `DirectIssueStrategy.DISTRIBUTED_LOCK` ([CouponIssueProperties.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponIssueProperties.kt))

이 클래스들은 `@Component` 없이도 빈이 됩니다. 시작 클래스에 `@ConfigurationPropertiesScan` 이 있기 때문입니다.

[SiteApplication.kt](../src/main/kotlin/com/site/SiteApplication.kt)

```kotlin
// JWT 로 직접 인증하므로 Spring Boot 기본 인메모리 사용자(+ 랜덤 비밀번호 로그)는 끈다
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class SiteApplication

fun main(args: Array<String>) {
    runApplication<SiteApplication>(*args)
}
```

- Kotlin 애너테이션에서 배열은 `[...]` 로 씁니다.
- `X::class` 는 Kotlin 클래스 참조입니다. (Java 의 `X.class`)
- `runApplication<SiteApplication>` 은 타입을 꺾쇠로 넘기는 Spring Boot 의 Kotlin 전용 함수입니다.

IDE 자동 완성을 위해 [spring-configuration-metadata.json](../src/main/resources/META-INF/spring-configuration-metadata.json) 에 각 설정의 설명과 기본값을 적어 두었습니다.

### `@Value` 와 비교

| | `@ConfigurationProperties` | `@Value("\${...}")` |
|---|---|---|
| 묶음 | 관련 설정을 한 클래스로 | 하나씩 |
| 타입 변환·기본값 | 생성자 기본값으로 자연스럽게 | 문자열 안에 `:기본값` |
| 이 프로젝트에서 | 대부분 | 로컬 관리자 계정 정도 |

## 6. 파라미터 이름이 실행 중에도 남는 이유 — `-java-parameters`

분산락의 키는 메서드 파라미터 이름으로 씁니다.

```kotlin
// CouponIssueService.kt
    @DistributedLock(key = "'coupon-event:' + #couponEventId")
    fun issueWithDistributedLock(couponEventId: Long, memberId: Long): IssuedCoupon {
```

`#couponEventId` 를 실제 값으로 바꾸려면 **실행 중에 파라미터 이름을 알아야** 합니다.

[DistributedLockAspect.kt](../src/main/kotlin/com/site/infrastructure/redis/lock/DistributedLockAspect.kt#L82-L86)

```kotlin
    // "'coupon-event:' + #couponEventId" 같은 SpEL 을 실제 파라미터 값으로 평가한다
    private fun resolveKey(joinPoint: ProceedingJoinPoint, key: String): String {
        val signature = joinPoint.signature as MethodSignature
        return CustomSpringELParser.getDynamicValue(signature.parameterNames, joinPoint.args, key).toString()
    }
```

컴파일된 바이트코드에는 기본적으로 파라미터 이름이 남지 않습니다. Spring Boot Gradle 플러그인은 Kotlin 플러그인이 있으면 컴파일러에 `-java-parameters` 옵션을 켜서 이름을 남깁니다. 그래서 `@PathVariable couponEventId: Long` 처럼 이름을 따로 적지 않아도 경로 변수가 연결됩니다.

## 7. `build.gradle.kts` 읽기

Gradle 설정도 Kotlin 으로 씁니다(Kotlin DSL). 주요 부분만 봅니다.

[build.gradle.kts](../build.gradle.kts#L21-L32)

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Boot 3.4.1 기본값(1.20.4)은 Docker Engine 29+ 가 거부하는 구버전 API 로 접속한다
extra["testcontainers.version"] = "1.21.4"
```

- `toolchain`: JDK 21 로 컴파일한다.
- `extra["testcontainers.version"]`: Spring Boot 가 관리하는 라이브러리 버전을 덮어쓴다. 로컬 Docker 29 가 Testcontainers 1.20.4 의 접속을 거부해서 올렸다.

의존성 블록의 구분:

| 구성 | 뜻 | 예 |
|---|---|---|
| `implementation` | 컴파일·실행 모두 필요 | `spring-boot-starter-web`, `jjwt` |
| `runtimeOnly` | 실행할 때만 필요 | `mysql-connector-j`, `micrometer-registry-prometheus` |
| `testImplementation` | 테스트에만 | `mockk`, `kotest-*`, `testcontainers` |

Kotlin 으로 Spring 을 쓸 때 꼭 필요한 두 의존성:

```kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
```

- `jackson-module-kotlin`: JSON 을 Kotlin 의 `data class` 로 바꿀 때 주 생성자, 파라미터 기본값, null 여부를 이해하게 한다. JSON 에 없는 선택 필드에 기본값을 넣고, non-null 필드가 비면 요청을 거절하는 것이 이 모듈 덕분이다. ([5장](05-web-and-errors.md))
- `kotlin-reflect`: Spring 이 Kotlin 클래스의 파라미터 기본값, null 여부를 읽는 데 쓴다.

[build.gradle.kts](../build.gradle.kts#L82-L97)

```kotlin
tasks.named<Test>("test") {
    useJUnitPlatform()
    // 운영 서버(UTC)와 같은 조건으로 돌린다. 서버 시간대에 기대는 코드가 생기면 테스트가 깨진다.
    jvmArgs("-Duser.timezone=UTC")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

ktlint {
    // 플러그인 기본 엔진(1.0.x)은 Kotlin 2.x 컴파일러와 충돌한다. Kotlin 2.1 기반 엔진을 쓴다.
    version.set("1.5.0")
}
```

- `-Xjsr305=strict`: Spring 코드에 붙은 `@Nullable` 같은 null 표시를 Kotlin 타입에 엄격하게 반영한다. Spring API 가 null 을 돌려줄 수 있으면 Kotlin 에서 `T?` 로 보인다.
- 테스트 JVM 을 UTC 로 돌리는 이유는 [4장](04-jpa-with-kotlin.md)의 시간대 이야기에서 다룹니다.
- ktlint 는 코드 스타일 검사기입니다. CI 가 `./gradlew ktlintCheck` 를 돌리고, 스타일 규칙은 [.editorconfig](../.editorconfig) 에 있습니다.

## 8. 프로필 — 환경마다 다른 설정

| 파일 | 언제 | 내용 |
|---|---|---|
| [application.properties](../src/main/resources/application.properties) | 항상 | 공통 (Flyway 검증, 스케줄러 스레드, 지표 설정) |
| [application-local.properties](../src/main/resources/application-local.properties) | 기본 (`spring.profiles.default=local`) | 로컬 DB·Redis 주소, 로컬 전용 JWT 키, 관리자 계정 |
| `application-prod.properties` | 운영 | 모든 접속 정보를 환경변수로만 받는다 |
| [application-test.properties](../src/test/resources/application-test.properties) | 테스트 | 스케줄러를 끄고 테스트가 직접 부른다 |

운영 프로필에서 환경변수가 빠지면, 엉뚱한 에러 대신 무엇이 빠졌는지 알려 주며 멈춥니다.

[ProdEnvironmentValidator.kt](../src/main/kotlin/com/site/independent/config/ProdEnvironmentValidator.kt)

```kotlin
/**
 * 운영 프로필에서 필수 환경변수가 빠졌으면, 무엇이 빠졌는지 알려주며 기동을 멈춘다.
 *
 * Spring 은 해석하지 못한 "${DB_URL}" 을 문자열 그대로 넘긴다. 그러면 "URL must start with 'jdbc'" 나
 * Base64 디코딩 오류처럼 원인과 동떨어진 에러가 엉뚱한 곳에서 난다.
 *
 * 빈을 만들기 전에 실행돼야 해서 @Component 가 아니라 EnvironmentPostProcessor 로 등록한다. (META-INF/spring.factories)
 */
class ProdEnvironmentValidator : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) return

        val missing = REQUIRED_VARIABLES.filter { environment.getProperty(it).isNullOrBlank() }
        check(missing.isEmpty()) {
            "운영 프로필에 필요한 환경변수가 없습니다: ${missing.joinToString()} (application-prod.properties 참고)"
        }
    }
```

### 설정으로 빈 고르기 — `@ConditionalOnProperty`

같은 인터페이스의 구현을 설정으로 바꿔 끼웁니다.

```kotlin
// LoggingAlimtalkSender.kt — biztalk.enabled 가 false 이거나 없으면
@ConditionalOnProperty(prefix = "biztalk", name = ["enabled"], havingValue = "false", matchIfMissing = true)

// SolapiAlimtalkSender.kt — biztalk.enabled=true 일 때만
@ConditionalOnProperty(prefix = "biztalk", name = ["enabled"], havingValue = "true")
```

스케줄러도 같은 방식으로 테스트에서 끕니다. (`coupon.queue.scheduler.enabled=false`)

## 왜 이렇게 했나 / 흔한 실수

| 증상 | 원인 | 이 프로젝트의 처리 |
|---|---|---|
| `@Transactional` 이 동작하지 않는다 | 클래스가 final 이라 프록시를 못 만듦 | `plugin.spring` |
| 엔티티 조회 시 `No default constructor` | 인자 없는 생성자 없음 | `plugin.jpa` |
| DTO 역직렬화 실패 | Jackson 이 Kotlin 생성자를 모름 | `jackson-module-kotlin` |
| 운영 기동 시 이해 안 되는 에러 | 환경변수 누락이 문자열 `${...}` 로 넘어감 | `ProdEnvironmentValidator` |
| SpEL `#couponEventId` 가 null | 파라미터 이름이 바이트코드에 없음 | Spring Boot 플러그인의 `-java-parameters` |

## 직접 해보기

1. `build.gradle.kts` 에서 `kotlin("plugin.spring")` 줄을 잠시 지우고 `./gradlew test --tests '*CouponIssueConcurrencyTest*'` 를 돌려 보세요. 어떤 에러가 나나요? (확인 후 되돌리세요)
2. `CouponQueueProperties` 에 `drainBatchSize` 설정을 추가해, 지금 `CouponQueueWorker` 에 상수로 있는 `DRAIN_BATCH_SIZE` 를 설정으로 바꿔 보세요. 메타데이터 JSON 도 함께 고칩니다.
3. 앱을 `./gradlew bootRun --args='--coupon.issue.direct-strategy=distributed-lock'` 로 띄우면 어느 클래스의 어떤 분기가 달라지나요?

다음 장: [3장. 프로젝트 구조와 계층](03-architecture.md)
