# 1장. Kotlin 기초 — 자바 개발자가 처음 막히는 것

**한 줄 요약**: Kotlin 은 "값이 바뀌는가", "null 일 수 있는가", "경우의 수가 몇 개인가"를 타입에 적게 하고, 컴파일러가 그것을 대신 확인해 줍니다.

이 장은 문법 사전이 아닙니다. 이 프로젝트 코드를 읽는 데 필요한 문법만, 프로젝트 코드로 설명합니다.

## 1. `val` 과 `var` — 바뀌는 값인가

- `val`: 한 번 정하면 바꿀 수 없다 (Java 의 `final`)
- `var`: 바꿀 수 있다

Kotlin 에서는 **기본이 `val`** 이고, 바뀌어야 할 이유가 있을 때만 `var` 를 씁니다.

[CouponEvent.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEvent.kt#L36-L42)

```kotlin
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(nullable = false, length = 50)
    var name: String = name
        protected set
```

- `id` 는 DB 가 정해 준 뒤 바뀌지 않으니 `val` 입니다.
- `name` 은 관리자가 수정할 수 있으니 `var` 지만, `protected set` 으로 **밖에서는 못 바꾸게** 막았습니다. 바꾸려면 `update(...)` 메서드를 거쳐야 하고, 그 안에서 "시작된 이벤트는 수정 불가" 규칙을 검사합니다.

`protected set` 처럼 **읽기와 쓰기의 공개 범위를 따로 정하는 것**은 Kotlin 프로퍼티의 특징입니다. Java 라면 private 필드 + public getter + 쓰기 메서드로 길게 써야 하는 내용입니다.

### 계산 프로퍼티

[CouponEvent.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEvent.kt#L72-L74)

```kotlin
    // backing field 가 없는 계산 프로퍼티는 컬럼으로 매핑되지 않는다
    val remainingQuantity: Int
        get() = totalQuantity - issuedQuantity
```

`get()` 을 직접 쓰면 값을 저장하지 않고 **읽을 때마다 계산**합니다. 남은 수량을 따로 저장하면 발급 수량과 어긋날 수 있으니, 계산으로 둡니다.

## 2. 문자열 템플릿

```kotlin
// Discount.kt
            DiscountType.PERCENTAGE -> "$value%"

// MemberRole.kt
    val authority: String
        get() = "ROLE_$name"
```

`"$변수"` 또는 `"${식}"` 으로 값을 끼워 넣습니다. 그래서 Spring 의 `@Value("${...}")` 를 Kotlin 에서 쓸 때는 `$` 를 `\$` 로 막아야 합니다.

[LocalAdminInitializer.kt](../src/main/kotlin/com/site/infrastructure/database/LocalAdminInitializer.kt#L18-L26)

```kotlin
 * Kotlin 문자열에서 $ 는 템플릿 문법이라 @Value 의 ${...} 는 \$ 로 이스케이프해야 한다.
 */
@Component
@Profile("local")
class LocalAdminInitializer(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${admin.init.email}") private val email: String,
    @Value("\${admin.init.password}") private val password: String,
```

여러 줄 문자열은 `"""..."""` 로 씁니다. Redis Lua 스크립트를 코드 안에 둘 때 씁니다. ([9장](09-redis-queue.md))

## 3. null 안전성 — 이 프로젝트에서 가장 중요한 문법

Kotlin 은 **null 이 될 수 있는 타입과 없는 타입을 구분**합니다.

| 타입 | 뜻 |
|---|---|
| `String` | 절대 null 이 아니다. null 을 넣으면 컴파일 에러 |
| `String?` | null 일 수 있다. 그대로는 메서드를 못 부른다 |

`String?` 를 다루는 도구는 네 가지입니다.

| 문법 | 뜻 | 예 |
|---|---|---|
| `a?.b()` | a 가 null 이면 b 를 부르지 않고 null | `request.getHeader(...)?.takeIf { ... }` |
| `a ?: b` | a 가 null 이면 b (엘비스 연산자) | `findByIdOrNull(id) ?: throw ...` |
| `a?.let { ... }` | a 가 null 이 아닐 때만 블록 실행 | `couponQueueStore.findResult(...)?.let { return ... }` |
| `a!!` | "절대 null 아님"이라고 우김. null 이면 NPE | 이 프로젝트 운영 코드에는 한 번도 없다 |

### 예 1 — 체인으로 이어 쓰기

[JwtAuthenticationFilter.kt](../src/main/kotlin/com/site/infrastructure/security/jwt/JwtAuthenticationFilter.kt#L53-L57)

```kotlin
    private fun resolveToken(request: HttpServletRequest): String? =
        request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() }
```

헤더가 없거나, `Bearer ` 로 시작하지 않거나, 토큰이 비어 있으면 어느 단계에서든 null 이 되고 이후 단계는 건너뜁니다. 반환 타입 `String?` 이 "토큰이 없을 수 있다"는 것을 호출하는 쪽에 알려 줍니다.

`takeIf { 조건 }` 은 조건이 참이면 자기 자신, 거짓이면 null 을 돌려줍니다.

### 예 2 — 없으면 예외

[AuthService.kt](../src/main/kotlin/com/site/domain/auth/service/AuthService.kt#L39-L42)

```kotlin
    fun login(email: String, rawPassword: String): TokenPair {
        val member = memberRepository.findByEmail(email)
            ?.takeIf { passwordEncoder.matches(rawPassword, it.password) }
            ?: throw ServiceException(ErrorCode.INVALID_CREDENTIALS)
```

"회원이 없다"와 "비밀번호가 틀렸다"가 같은 null 로 모여서 같은 에러가 됩니다. 어느 쪽이 틀렸는지 알려 주면 가입된 이메일을 알아낼 수 있으니 일부러 하나로 합친 것입니다.

`throw` 도 식(expression)이라 `?:` 오른쪽에 바로 쓸 수 있습니다.

### 예 3 — 없으면 일찍 돌아가기

[CouponNotificationService.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponNotificationService.kt#L29-L33)

```kotlin
    fun notifyIssued(event: CouponIssuedEvent): NotificationResult {
        val member = memberRepository.findByIdOrNull(event.memberId)
            ?: return NotificationResult.Skipped("회원이 없습니다")
        val phoneNumber = member.phoneNumber
            ?: return NotificationResult.Skipped("휴대폰 번호가 없습니다")
```

`member.phoneNumber` 의 타입은 `String?` 입니다. ([Member.kt](../src/main/kotlin/com/site/domain/member/entity/Member.kt#L54-L57) 주석: "nullable 타입이라 쓰는 쪽에서 null 처리를 강제받는다") 엘비스 다음 줄부터 `phoneNumber` 는 `String` 으로 취급됩니다. 이것을 **스마트 캐스트**라고 합니다.

### Optional 대신 nullable

[MemberRepository.kt](../src/main/kotlin/com/site/domain/member/repository/MemberRepository.kt)

```kotlin
interface MemberRepository : JpaRepository<Member, Long> {

    // Optional<Member> 대신 nullable 타입으로 받는다
    fun findByEmail(email: String): Member?
```

Spring Data 는 반환 타입을 `Member?` 로 적으면 없을 때 null 을 줍니다. 기본 제공 `findById` 는 `Optional` 을 돌려주므로, Spring Data 가 Kotlin 용으로 준비한 확장 함수 `findByIdOrNull` 을 씁니다.

```kotlin
// CouponIssueService.kt (import 와 사용 부분만, 중간 생략)
import org.springframework.data.repository.findByIdOrNull

        couponEventRepository.findByIdOrNull(couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)
```

## 4. 함수 — 식 본문, 기본값, 이름 있는 인자

### 식 본문

본문이 식 하나면 `=` 로 씁니다.

[DiscountType.kt](../src/main/kotlin/com/site/domain/coupon/entity/DiscountType.kt)

```kotlin
enum class DiscountType(
    private val validRange: IntRange,
) {
    /** 정액 할인 (원) */
    FIXED_AMOUNT(1..1_000_000),

    /** 정률 할인 (%) */
    PERCENTAGE(1..100),
    ;

    fun isValidValue(value: Int): Boolean = value in validRange
}
```

- `1..100` 은 1 부터 100 까지의 범위(`IntRange`), `value in validRange` 는 범위 안에 있는지 확인합니다.
- `1_000_000` 처럼 숫자에 밑줄을 넣어 읽기 쉽게 쓸 수 있습니다.
- enum 상수가 자기만의 값(허용 범위)을 가집니다. "정률은 1~100%" 같은 규칙을 if 문 대신 데이터로 적은 것입니다.

### 기본값과 이름 있는 인자

[CouponEvent.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEvent.kt#L27-L34)

```kotlin
class CouponEvent(
    name: String,
    discount: Discount,
    period: EventPeriod,
    totalQuantity: Int,
    issueMode: CouponIssueMode = CouponIssueMode.DIRECT,
    couponValidityDays: Int = DEFAULT_COUPON_VALIDITY_DAYS,
) : BaseTimeEntity() {
```

기본값이 있는 파라미터는 생략할 수 있습니다. 호출할 때는 이름을 붙여 순서와 상관없이 넘길 수 있습니다.

[MemberService.kt](../src/main/kotlin/com/site/domain/member/service/MemberService.kt#L31-L36)

```kotlin
        val member = Member(
            email = email,
            password = passwordEncoder.encode(rawPassword),
            nickname = nickname,
            phoneNumber = phoneNumber,
        )
```

`role` 은 넘기지 않았으므로 기본값 `MemberRole.USER` 가 들어갑니다. Java 에서 오버로딩이나 빌더 패턴으로 하던 일을 문법 하나로 합니다.

마지막 파라미터 뒤의 쉼표(trailing comma)는 허용되고, 이 프로젝트는 항상 붙입니다. 줄을 추가할 때 diff 가 한 줄만 바뀝니다.

## 5. 클래스 — 주 생성자, `init`, `data class`

### 주 생성자와 `init`

[EventPeriod.kt](../src/main/kotlin/com/site/domain/coupon/entity/EventPeriod.kt#L15-L31)

```kotlin
@Embeddable
class EventPeriod(
    @Column(name = "start_at", nullable = false)
    val startAt: LocalDateTime,

    @Column(name = "end_at", nullable = false)
    val endAt: LocalDateTime,
) {
    init {
        if (!startAt.isBefore(endAt)) {
            throw ServiceException(ErrorCode.INVALID_COUPON_EVENT_PERIOD)
        }
    }

    fun isUpcoming(now: LocalDateTime): Boolean = now.isBefore(startAt)

    fun isEnded(now: LocalDateTime): Boolean = !now.isBefore(endAt)
```

- 클래스 이름 옆 괄호가 **주 생성자**입니다. 파라미터에 `val` 을 붙이면 그대로 프로퍼티가 됩니다.
- `init` 블록은 생성자가 불릴 때 실행됩니다. 여기서 검사하면 **잘못된 값으로는 객체를 만들 수조차 없습니다**.

`CouponEvent` 처럼 파라미터에 `val` 이 **없으면** 그 파라미터는 생성자 안에서만 쓰는 값입니다. 프로퍼티는 본문에서 따로 선언하고(`var name: String = name`) 접근 범위를 조절합니다.

### `require` 와 예외의 구분

[CouponEvent.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEvent.kt#L76-L80)

```kotlin
    init {
        // 요청 DTO 검증(@Positive)을 통과했다면 절대 깨질 수 없는 조건 → 프로그래밍 오류이므로 require
        require(totalQuantity > 0) { "totalQuantity must be positive: $totalQuantity" }
        require(couponValidityDays in VALIDITY_DAYS_RANGE) { "couponValidityDays must be in $VALIDITY_DAYS_RANGE: $couponValidityDays" }
    }
```

| 함수 | 던지는 예외 | 이 프로젝트에서의 의미 |
|---|---|---|
| `require(조건) { 메시지 }` | `IllegalArgumentException` | 호출한 쪽의 버그 (일어나면 안 되는 일) |
| `check(조건) { 메시지 }` | `IllegalStateException` | 상태가 이상함 (예: 운영 환경변수 누락) |
| `error(메시지)` | `IllegalStateException` | 여기 오면 안 되는 분기 |
| `throw ServiceException(ErrorCode.X)` | 직접 정의 | 사용자에게 알려 줄 비즈니스 실패 (소진, 종료 ...) |

### `data class`

값을 담는 클래스는 `data class` 로 씁니다. `equals`, `hashCode`, `toString`, `copy`, 구조 분해를 컴파일러가 만들어 줍니다.

[CouponIssuedEvent.kt](../src/main/kotlin/com/site/domain/coupon/event/CouponIssuedEvent.kt)

```kotlin
data class CouponIssuedEvent(
    val issuedCouponId: Long,
    val couponEventId: Long,
    val memberId: Long,
)
```

`copy` 는 일부 값만 바꾼 새 객체를 만듭니다.

[StartedCouponEventCache.kt](../src/main/kotlin/com/site/domain/coupon/service/StartedCouponEventCache.kt#L52-L55)

```kotlin
    /** 발급 단계에서 소진을 확인했을 때 부른다. 이후 이 이벤트의 요청은 DB 를 거치지 않고 거절된다. */
    fun markSoldOut(couponEventId: Long) {
        events.computeIfPresent(couponEventId) { _, event -> event.copy(soldOut = true) }
    }
```

객체를 고치지 않고 새로 만들어 바꿔 끼우기 때문에, 여러 스레드가 같은 객체를 동시에 읽어도 안전합니다.

**JPA 엔티티는 `data class` 로 만들지 않습니다.** 이유는 [Member.kt](../src/main/kotlin/com/site/domain/member/entity/Member.kt#L15-L19) 주석에 있습니다.

```kotlin
/**
 * - data class 로 만들지 않는다: equals/hashCode/toString 자동 생성이 지연 로딩·연관관계와 충돌한다.
 * - 생성자 파라미터는 val/var 없이 받고, 프로퍼티는 protected set 으로 외부 변경을 막는다.
 * - 기본 생성자는 kotlin("plugin.jpa"), 프록시용 open 은 allOpen 설정이 만들어준다.
 */
```

### 구조 분해

```kotlin
// RedisRefreshTokenStore.kt
        val (status, owner) = result.split(STATUS_SEPARATOR, limit = 2)
```

리스트나 data class 를 여러 변수로 한 번에 나눠 받습니다. 테스트의 `withData` 에서도 `{ (type, value, valid) -> ... }` 처럼 씁니다. ([10장](10-testing.md))

## 6. `object` 와 `companion object` — static 이 없는 언어

Kotlin 에는 `static` 이 없습니다. 대신 두 가지를 씁니다.

**`object`** — 인스턴스가 하나뿐인 클래스(싱글턴)

[AsyncExecutors.kt](../src/main/kotlin/com/site/independent/config/AsyncExecutors.kt)

```kotlin
object AsyncExecutors {
    const val NOTIFICATION = "notificationExecutor"
}
```

**`companion object`** — 클래스에 딸린 정적 멤버

[CouponEventStatus.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEventStatus.kt#L38-L50)

```kotlin
    companion object {
        fun of(
            period: EventPeriod,
            soldOut: Boolean,
            now: LocalDateTime,
        ): CouponEventStatus =
            when {
                period.isUpcoming(now) -> UPCOMING
                period.isEnded(now) -> ENDED
                soldOut -> SOLD_OUT
                else -> ONGOING
            }
    }
```

`CouponEventStatus.of(...)` 처럼 클래스 이름으로 부릅니다. 상수는 `const val` 로 둡니다. (`CouponEvent.DEFAULT_COUPON_VALIDITY_DAYS`)

## 7. `when` 과 `sealed` — 경우의 수를 컴파일러가 센다

### `when` 은 식이다

`when` 은 Java 의 `switch` 보다 강하고, 값을 돌려줍니다.

[CouponEventStatus.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEventStatus.kt#L25-L36)

```kotlin
    /**
     * 발급할 수 있는 상태가 아니라면 이유에 맞는 예외를 던진다.
     * enum 을 대상으로 한 when 은 모든 값을 다루지 않으면 컴파일 에러가 난다. 나중에 상태가 추가돼도 여기서 놓치지 않는다.
     */
    fun checkIssuable() {
        when (this) {
            UPCOMING -> throw ServiceException(ErrorCode.COUPON_EVENT_NOT_STARTED)
            ENDED -> throw ServiceException(ErrorCode.COUPON_EVENT_ENDED)
            SOLD_OUT -> throw ServiceException(ErrorCode.COUPON_SOLD_OUT)
            ONGOING -> Unit
        }
    }
```

`else` 를 쓰지 않은 것이 핵심입니다. 나중에 `PAUSED` 같은 상태가 생기면 이 `when` 이 컴파일 에러가 나서, 새 상태를 어떻게 처리할지 반드시 정하게 됩니다.

### `sealed` — "결과는 이것들뿐"

[CouponQueueStore.kt](../src/main/kotlin/com/site/domain/coupon/queue/port/CouponQueueStore.kt#L75-L87)

```kotlin
/** 줄 서기 결과. 예전에는 -1, -2 같은 숫자로 돌려줬지만 의미를 타입으로 드러낸다. */
sealed interface EnqueueResult {
    /** @property rank 0부터 시작하는 순위 */
    data class Enqueued(
        val rank: Long,
    ) : EnqueueResult

    /** 이미 처리 중이거나 결과가 있다 */
    data object AlreadyProcessed : EnqueueResult

    /** 대기열이 닫혔다 (소진·종료) */
    data object Closed : EnqueueResult
}
```

- `sealed` 는 하위 타입이 이 파일 안에 정의된 것뿐이라고 컴파일러에 알려 줍니다.
- 정보가 있는 결과는 `data class`, 정보가 없는 결과는 `data object`(싱글턴) 로 둡니다.
- 예전 구현은 `-1`, `-2` 같은 숫자를 돌려줬습니다. 숫자는 의미를 주석으로만 알 수 있고, 호출하는 쪽이 경우 하나를 빠뜨려도 컴파일러가 모릅니다.

쓰는 쪽:

[CouponQueueService.kt](../src/main/kotlin/com/site/domain/coupon/queue/CouponQueueService.kt#L26-L33)

```kotlin
        return when (val result = couponQueueStore.enqueue(couponEventId, memberId)) {
            is EnqueueResult.Enqueued -> {
                couponQueueStore.activate(couponEventId)
                CouponQueueStatus.Waiting(position = result.rank + 1)
            }
            EnqueueResult.AlreadyProcessed -> getStatus(couponEventId, memberId)
            EnqueueResult.Closed -> throw ServiceException(closedErrorCode(couponEventId))
        }
```

- `when (val result = ...)` 로 값을 받으면서 바로 분기합니다.
- `is EnqueueResult.Enqueued` 분기 안에서는 `result.rank` 를 바로 씁니다. (스마트 캐스트)
- `data object` 는 `is` 없이 값으로 비교합니다.

같은 패턴이 [AlimtalkSendResult](../src/main/kotlin/com/site/domain/notification/port/AlimtalkSender.kt), [ConsumeResult](../src/main/kotlin/com/site/domain/auth/port/RefreshTokenStore.kt), [NotificationResult](../src/main/kotlin/com/site/domain/notification/NotificationResult.kt), [CouponQueueStatus](../src/main/kotlin/com/site/domain/coupon/queue/CouponQueueStatus.kt) 에서 반복됩니다. **실패를 예외 대신 반환값으로 돌려주고, 처리를 빠뜨리면 컴파일이 안 되게** 하는 것이 이 프로젝트의 일관된 습관입니다.

### 조건만 있는 `when`

```kotlin
// IssuedCoupon.kt
    fun status(now: LocalDateTime): IssuedCouponStatus =
        when {
            usedAt != null -> IssuedCouponStatus.USED
            !now.isBefore(expiresAt) -> IssuedCouponStatus.EXPIRED
            else -> IssuedCouponStatus.AVAILABLE
        }
```

`when` 뒤에 대상이 없으면 if-else if 사슬처럼 위에서부터 참인 첫 분기를 고릅니다.

## 8. 확장 함수 — 남의 클래스에 함수 붙이기

클래스를 고치지 않고 함수를 추가한 것처럼 쓸 수 있습니다.

[Masking.kt](../src/main/kotlin/com/site/independent/util/Masking.kt)

```kotlin
/** 로그에 휴대폰 번호를 그대로 남기지 않는다. 01012345678 → 010****5678 */
fun String.maskPhoneNumber(): String = if (length < 8) "****" else take(3) + "****" + takeLast(4)
```

`phoneNumber.maskPhoneNumber()` 처럼 부릅니다. 본문의 `length`, `take` 는 `this`(그 문자열)의 것입니다.

이 프로젝트는 확장 함수를 **변환 코드를 둘 곳**으로 많이 씁니다.

[CouponEventRequest.kt](../src/main/kotlin/com/site/presentation/admin/dto/CouponEventRequest.kt#L47-L57)

```kotlin
fun CouponEventRequest.toCommand(): CouponEventCommand =
    CouponEventCommand(
        name = name,
        discountType = discountType,
        discountValue = discountValue,
        totalQuantity = totalQuantity,
        startAt = startAt,
        endAt = endAt,
        issueMode = issueMode,
        couponValidityDays = couponValidityDays,
    )
```

DTO → 명령 변환을 presentation 패키지에 두면, domain 의 `CouponEventCommand` 는 요청 DTO 를 몰라도 됩니다. ([3장](03-architecture.md))

`private` 확장 함수는 그 파일(클래스) 안에서만 쓰는 도우미가 됩니다.

[JwtTokenProvider.kt](../src/main/kotlin/com/site/infrastructure/security/jwt/JwtTokenProvider.kt#L72-L79)

```kotlin
    // when + is 로 예외 타입별 분기 (스마트 캐스트)
    private fun JwtException.toErrorCode(): ErrorCode =
        when (this) {
            is ExpiredJwtException -> ErrorCode.EXPIRED_TOKEN
            is UnsupportedJwtException -> ErrorCode.UNSUPPORTED_TOKEN
            is MalformedJwtException, is JwtSecurityException -> ErrorCode.MALFORMED_TOKEN
            else -> ErrorCode.INVALID_TOKEN
        }
```

`JwtSecurityException` 은 import 별칭입니다. (`import io.jsonwebtoken.security.SecurityException as JwtSecurityException`) Kotlin 표준의 `SecurityException` 과 이름이 겹쳐서 다른 이름을 붙였습니다.

## 9. 람다와 고차 함수

함수를 값처럼 넘길 수 있습니다.

[PageResponse.kt](../src/main/kotlin/com/site/independent/response/PageResponse.kt#L18-L27)

```kotlin
// Page<엔티티> → PageResponse<DTO>. 변환 방법은 람다로 받는다.
fun <T, R> Page<T>.toPageResponse(transform: (T) -> R): PageResponse<R> =
    PageResponse(
        content = content.map(transform),
        page = number,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
        hasNext = hasNext(),
    )
```

- `(T) -> R` 는 "T 를 받아 R 을 돌려주는 함수" 타입입니다.
- `<T, R>` 은 제네릭입니다. 어떤 엔티티든, 어떤 DTO 든 쓸 수 있습니다.

쓰는 쪽:

```kotlin
// CouponController.kt
        return SuccessCode.GET_COUPON_EVENTS_SUCCESS.toResponseEntity(events.toPageResponse { it.toSummaryResponse(now) })
```

마지막 인자가 람다면 괄호 밖으로 뺄 수 있고(trailing lambda), 파라미터가 하나면 `it` 으로 부릅니다.

## 10. 스코프 함수 — `apply`, `also`, `let`, `run`

객체 하나를 두고 블록을 실행하는 함수들입니다. 처음엔 헷갈리지만 이 프로젝트에서 쓰는 방식은 정해져 있습니다.

| 함수 | 블록 안에서 객체 | 돌려주는 값 | 이 프로젝트에서 쓰는 곳 |
|---|---|---|---|
| `apply` | `this` | 객체 자신 | 객체를 만들면서 설정 (`ThreadPoolTaskExecutor().apply { ... }`) |
| `also` | `it` | 객체 자신 | 값은 그대로 두고 곁가지 일 (`.also { metrics.recordDirectIssued() }`) |
| `let` | `it` | 블록 결과 | null 이 아닐 때만 실행 (`?.let { ... }`) |
| `run` | `this` | 블록 결과 | 테스트에서 객체 하나로 여러 호출 (`provider().run { ... }`) |
| `takeIf` | `it` | 조건이 참이면 객체, 아니면 null | 조건부 체인 |

[AsyncConfig.kt](../src/main/kotlin/com/site/infrastructure/async/AsyncConfig.kt#L23-L34)

```kotlin
    @Bean(AsyncExecutors.NOTIFICATION)
    fun notificationExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 8
            queueCapacity = 1_000
            setThreadNamePrefix("notification-")
            setRejectedExecutionHandler { _, _ -> log.warn("알림 발송 큐가 가득 차서 빠른 발송을 건너뜁니다. Outbox 스케줄러가 나중에 보냅니다.") }
            // 배포 등으로 종료될 때 이미 받은 알림 작업은 최대 10초까지 마저 처리한다
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
```

`corePoolSize = 4` 는 Java 의 `setCorePoolSize(4)` 입니다. Kotlin 은 Java 의 getter/setter 를 프로퍼티처럼 부를 수 있습니다.

[JwtTokenProvider.kt](../src/main/kotlin/com/site/infrastructure/security/jwt/JwtTokenProvider.kt#L52-L55)

```kotlin
    override fun createRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also { secureRandom.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
```

## 11. 컬렉션 함수

반복문 대신 컬렉션 함수를 이어 씁니다. 대기열 일괄 발급 코드가 좋은 예입니다.

[CouponIssueService.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponIssueService.kt#L101-L116)

```kotlin
        val alreadyIssued = issuedCouponRepository.findIssuedMemberIds(couponEventId, memberIds).toSet()
        val candidates = memberIds.filterNot { it in alreadyIssued }
        val status = couponEvent.status(now)
        val winners = if (status == CouponEventStatus.ONGOING) candidates.take(couponEvent.remainingQuantity) else emptyList()

        if (winners.isNotEmpty()) {
            couponEvent.issue(now, quantity = winners.size)
            val expiresAt = couponEvent.couponExpiresAt(now)
            issuedCouponRepository.saveAll(winners.map { IssuedCoupon(couponEventId = couponEventId, memberId = it, expiresAt = expiresAt) })
                .forEach { notificationOutboxService.recordCouponIssued(CouponIssuedEvent(it.id, couponEventId, it.memberId)) }
        }

        val issued = alreadyIssued + winners
        return QueuedBatchIssueResult(
            issued = memberIds.filter { it in issued },
            rejected = candidates.drop(winners.size),
```

| 함수 | 하는 일 |
|---|---|
| `filter` / `filterNot` | 조건에 맞는(맞지 않는) 것만 |
| `take(n)` / `drop(n)` | 앞에서 n 개 / 앞 n 개를 뺀 나머지 |
| `map` | 하나씩 바꾸기 |
| `toSet()` | 집합으로 (`in` 확인이 빨라진다) |
| `set + list` | 합집합 |
| `if (...) a else b` | if 도 식이라 값을 돌려준다 |

다른 곳에서 쓰는 것들:

```kotlin
// CouponWalletService.kt — ID 로 바로 찾을 수 있게 Map 으로
        val events = couponEventRepository.findAllById(coupons.content.map { it.couponEventId }.toSet())
            .associateBy { it.id }

// CouponQueueWorker.kt — 회원마다 결과를 붙인 Map, 결과별로 묶기
        issued.associateWith { CouponQueueResult.ISSUED } + rejected.associateWith { rejectReason.toQueueResult() }
        results.entries.groupBy({ it.value }, { it.key }).forEach { (result, ids) ->
```

Kotlin 은 **읽기 전용 컬렉션(`List`, `Map`)과 바꿀 수 있는 컬렉션(`MutableList`, `MutableMap`)을 타입으로 구분**합니다. 그래서 Java 라이브러리가 바꿀 수 있는 Map 을 요구하면 복사본을 넘깁니다.

```kotlin
// SolapiAlimtalkSender.kt
                // Kotlin 은 읽기 전용 Map 과 MutableMap 을 타입으로 구분한다. SDK 가 MutableMap 을 요구해서 복사본을 넘긴다.
                variables = variables.toMutableMap(),
```

## 12. 예외를 값으로 — `try` 식과 `runCatching`

`try` 도 값을 돌려주는 식입니다.

[CouponIssueService.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponIssueService.kt#L144-L152)

```kotlin
    private fun saveIssuedCoupon(couponEvent: CouponEvent, memberId: Long): IssuedCoupon {
        val couponEventId = couponEvent.id
        val issuedCoupon = try {
            issuedCouponRepository.saveAndFlush(
                IssuedCoupon(couponEventId = couponEventId, memberId = memberId, expiresAt = couponEvent.couponExpiresAt(now())),
            )
        } catch (e: DataIntegrityViolationException) {
            throw ServiceException(ErrorCode.ALREADY_ISSUED_COUPON)
        }
```

`runCatching` 은 예외를 `Result` 로 감싸서 성공과 실패를 각각 값으로 바꿉니다.

[SolapiAlimtalkSender.kt](../src/main/kotlin/com/site/infrastructure/biztalk/SolapiAlimtalkSender.kt)

```kotlin
    /** runCatching: 예외를 Result 로 감싸서, 성공/실패를 각각 값으로 변환한다. */
    override fun send(message: AlimtalkMessage): AlimtalkSendResult =
        runCatching { messageService.sendOne(SingleMessageSendingRequest(message.toSolapiMessage())) }
            .fold(
                onSuccess = { AlimtalkSendResult.Success },
                onFailure = {
                    AlimtalkSendResult.Failure(reason = it.message ?: it.javaClass.simpleName, retryable = it.isRetryable())
                },
            )
```

Kotlin 에는 **checked exception 이 없습니다.** Java 처럼 `throws` 를 선언하거나 강제로 catch 할 필요가 없습니다. 그래서 실패를 알려야 할 때는 이 프로젝트처럼 sealed 결과 타입을 돌려주는 방식이 더 안전합니다. 호출하는 쪽이 처리를 빠뜨리면 컴파일러가 잡기 때문입니다.

## 13. 그 밖에 코드에서 마주칠 것

| 문법 | 어디서 | 뜻 |
|---|---|---|
| `lateinit var createdAt: LocalDateTime` | [BaseTimeEntity.kt](../src/main/kotlin/com/site/domain/common/entity/BaseTimeEntity.kt) | null 이 아닌 타입인데 생성 시점에는 값이 없다. JPA 가 나중에 채운다 |
| `@Volatile var delay` | [FakeAlimtalkSender.kt](../src/test/kotlin/com/site/support/FakeAlimtalkSender.kt) | 다른 스레드에서 바꾼 값이 바로 보이게 (Java 의 `volatile`) |
| `Nothing?` | [SuccessResponse.kt](../src/main/kotlin/com/site/independent/response/SuccessResponse.kt) | null 하나만 담을 수 있는 타입. "돌려줄 데이터 없음" |
| `Long::class.javaObjectType` | [RedisCouponQueueStore.kt](../src/main/kotlin/com/site/infrastructure/redis/queue/RedisCouponQueueStore.kt) | `Long::class.java` 는 원시 `long`, 래퍼 `java.lang.Long` 이 필요할 때 |
| `*args` | [SiteApplication.kt](../src/main/kotlin/com/site/SiteApplication.kt) | 배열을 가변 인자로 펼치기 |
| `` fun `한글 테스트 이름`() `` | 테스트 전반 | 백틱으로 감싸면 공백·한글로 함수 이름을 쓸 수 있다 |
| `Unit` | `ONGOING -> Unit` | 돌려줄 값이 없음 (Java 의 `void`). when 분기에서 "아무것도 안 함" |

## 흔한 실수

| 실수 | 왜 문제인가 | 대신 |
|---|---|---|
| `!!` 로 null 을 무시한다 | NPE 를 Kotlin 으로 옮겨 왔을 뿐이다 | `?:` 로 예외나 기본값을 정한다 |
| 엔티티를 `data class` 로 만든다 | 지연 로딩·연관관계와 equals/hashCode 가 충돌한다 | 일반 `class` + `protected set` |
| `when` 에 습관처럼 `else` 를 쓴다 | 새 enum 값·sealed 하위 타입을 놓쳐도 컴파일이 된다 | enum·sealed 는 `else` 없이 모든 경우를 쓴다 |
| `var` 를 기본으로 쓴다 | 어디서 바뀌는지 추적해야 할 곳이 늘어난다 | 기본은 `val`, 바꿀 이유가 있을 때만 `var` |
| `@Value("${...}")` 를 그대로 쓴다 | 문자열 템플릿으로 해석돼 컴파일 에러 | `"\${...}"` |

## 직접 해보기

1. `CouponIssueMode` 에 세 번째 방식 `LOTTERY`(추첨)를 추가해 보세요. 컴파일 에러가 나는 곳을 모두 찾고, 왜 거기서 에러가 나는지 설명해 보세요. (확인 후 되돌리세요)
2. [JwtAuthenticationFilter.resolveToken](../src/main/kotlin/com/site/infrastructure/security/jwt/JwtAuthenticationFilter.kt) 을 `?.` 와 `takeIf` 없이 if 문으로 다시 써 보고, 두 버전의 줄 수와 읽기 쉬움을 비교해 보세요.
3. `IssuedCoupon.status(now)` 의 `when` 에서 두 조건의 순서를 바꾸면 어떤 쿠폰의 상태가 달라지나요?
4. `CouponQueueWorker.toQueueResults()` 를 for 문과 `mutableMapOf()` 로 다시 써 보세요.

다음 장: [2장. 스프링이 Kotlin 을 만나면](02-spring-meets-kotlin.md)
