# 4장. JPA 엔티티를 Kotlin 으로

**한 줄 요약**: 엔티티는 "잘못된 상태로는 만들 수 없고, 정해진 메서드로만 바뀌는" 객체로 만듭니다. 시간은 인자로 받고, 테이블은 Flyway 가 만들고, Hibernate 는 둘이 맞는지만 검사합니다.

## 1. JPA 가 하는 일

JPA(구현체는 Hibernate)는 **클래스와 테이블을 연결**합니다.

- `couponEventRepository.save(event)` → `INSERT`
- `findByIdOrNull(id)` → `SELECT` 후 객체로
- 트랜잭션 안에서 조회한 객체의 값을 바꾸면 → 커밋할 때 `UPDATE` (변경 감지, dirty checking)

마지막이 처음엔 가장 낯섭니다. `save()` 를 부르지 않아도 UPDATE 가 나갑니다. 뒤에서 다시 봅니다.

## 2. 엔티티 해부 — `CouponEvent`

[CouponEvent.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEvent.kt#L19-L80)

```kotlin
/**
 * 선착순 쿠폰 발급 이벤트.
 *
 * 시간에 따라 달라지는 판단(status, 수정 가능 여부)은 LocalDateTime.now() 를 직접 부르지 않고 now 를 인자로 받는다.
 * 덕분에 이 클래스는 스프링 없이, 시각을 마음대로 넣어가며 단위 테스트할 수 있다.
 */
@Entity
@Table(name = "coupon_event")
class CouponEvent(
    name: String,
    discount: Discount,
    period: EventPeriod,
    totalQuantity: Int,
    issueMode: CouponIssueMode = CouponIssueMode.DIRECT,
    couponValidityDays: Int = DEFAULT_COUPON_VALIDITY_DAYS,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(nullable = false, length = 50)
    var name: String = name
        protected set

    @Embedded
    var discount: Discount = discount
        protected set

    @Embedded
    var period: EventPeriod = period
        protected set

    @Column(nullable = false)
    var totalQuantity: Int = totalQuantity
        protected set

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    var issueMode: CouponIssueMode = issueMode
        protected set

    /** 발급 후 며칠 동안 쓸 수 있는지 */
    @Column(nullable = false)
    var couponValidityDays: Int = couponValidityDays
        protected set

    // 3단계(선착순 발급)에서 동시성 문제의 주인공이 될 컬럼
    @Column(nullable = false)
    var issuedQuantity: Int = 0
        protected set

    // backing field 가 없는 계산 프로퍼티는 컬럼으로 매핑되지 않는다
    val remainingQuantity: Int
        get() = totalQuantity - issuedQuantity

    init {
        // 요청 DTO 검증(@Positive)을 통과했다면 절대 깨질 수 없는 조건 → 프로그래밍 오류이므로 require
        require(totalQuantity > 0) { "totalQuantity must be positive: $totalQuantity" }
        require(couponValidityDays in VALIDITY_DAYS_RANGE) { "couponValidityDays must be in $VALIDITY_DAYS_RANGE: $couponValidityDays" }
    }
```

한 줄씩 짚어 봅니다.

| 코드 | 왜 |
|---|---|
| 생성자 파라미터에 `val`/`var` 없음 | 생성자로 받은 값을 프로퍼티에 **옮겨 담기만** 한다. 프로퍼티의 공개 범위를 따로 정하려고 |
| `val id: Long = 0L` + `IDENTITY` | DB 의 AUTO_INCREMENT 가 정한다. 저장 전에는 0 |
| `var ... protected set` | 읽기는 누구나, 쓰기는 이 클래스(와 JPA 프록시)만 |
| `@Embedded var discount` | 값 객체 `Discount` 의 필드가 이 테이블의 컬럼 두 개로 들어간다 |
| `issuedQuantity = 0` | 생성자로 받지 않는다. 만들 때는 항상 0 |
| `remainingQuantity` (get 만) | 저장하지 않고 계산한다. 컬럼이 아니다 |
| `init { require(...) }` | 잘못된 수량으로는 만들 수 없다 |

**값을 바꾸는 방법은 메서드뿐입니다.**

[CouponEvent.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEvent.kt#L114-L133)

```kotlin
    /**
     * 메모리에서 issuedQuantity 를 quantity 만큼 올린다. (커밋 시점에 dirty checking 으로 UPDATE)
     * "읽고 → 판단하고 → 쓰는" 구조라서, 동시에 여러 요청이 같은 값을 읽으면 Lost Update 가 생긴다.
     * 이 메서드를 안전하게 쓰려면 호출하는 쪽에서 락으로 감싸야 한다. (CouponIssueService 참고)
     */
    fun issue(now: LocalDateTime, quantity: Int = 1) {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        checkIssuable(now)
        if (remainingQuantity < quantity) {
            throw ServiceException(ErrorCode.COUPON_SOLD_OUT)
        }
        issuedQuantity += quantity
    }

    /** 시작된 이벤트는 이미 쿠폰을 받은 사람이 있을 수 있으므로 수정·삭제를 막는다. */
    fun checkModifiable(now: LocalDateTime) {
        if (status(now) != CouponEventStatus.UPCOMING) {
            throw ServiceException(ErrorCode.COUPON_EVENT_ALREADY_STARTED)
        }
    }
```

`issuedQuantity` 를 밖에서 `event.issuedQuantity = 50` 처럼 바꿀 수 없으니, 수량은 반드시 `issue()` 의 검사를 거쳐 늘어납니다. 이 메서드의 주석이 예고하는 문제(Lost Update)가 [7장](07-concurrency.md)의 주제입니다.

## 3. 값 객체 — `@Embeddable`

할인(종류 + 값)과 기간(시작 + 종료)은 따로 있으면 의미가 없는 값의 묶음입니다. 별도 테이블 없이 같은 테이블의 컬럼으로 저장합니다.

[EventPeriod.kt](../src/main/kotlin/com/site/domain/coupon/entity/EventPeriod.kt#L9-L38)

```kotlin
/**
 * 발급 기간 값 객체. [startAt, endAt) — 시작 시각은 포함, 종료 시각은 포함하지 않는다.
 *
 * "시작 < 종료"는 언제나 참이어야 하는 규칙이라 init 에서 검사한다.
 * 반면 "종료가 현재 이후"는 시간이 흐르면 깨지는 규칙이라 객체 불변식이 아니다. 그래서 now 를 받는 메서드로 분리했다.
 */
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

    fun requireNotEnded(now: LocalDateTime) {
        if (isEnded(now)) {
            throw ServiceException(ErrorCode.INVALID_COUPON_EVENT_PERIOD)
        }
    }
}
```

주석의 구분이 중요합니다.

| 규칙 | 시간이 지나도 참인가 | 어디서 검사 |
|---|---|---|
| 시작 < 종료 | 항상 참 | `init` (객체 불변식) |
| 종료 > 지금 | 시간이 지나면 거짓이 된다 | `now` 를 받는 메서드 (`requireNotEnded`) |

"종료 > 지금"을 `init` 에 넣으면, 이미 끝난 기간으로는 `EventPeriod` 를 만들 수 없게 됩니다. 지난 이벤트의 기간을 다루는 코드(테스트 데이터, 기간 비교)가 생성 단계에서 깨집니다. JPA 가 DB 에서 읽을 때는 `init` 을 거치지 않아 조회만으로는 드러나지 않으니 더 늦게 발견됩니다.

## 4. 상태는 저장하지 않고 계산한다

[CouponEventStatus.kt](../src/main/kotlin/com/site/domain/coupon/entity/CouponEventStatus.kt#L7-L10)

```kotlin
/**
 * DB 에 저장하지 않고 "현재 시각 + 발급 수량"으로 그때그때 계산한다.
 * 저장해두면 시작/종료 시각이 지날 때마다 상태를 바꿔줄 배치가 필요해진다.
 */
```

```kotlin
// CouponEvent.kt
    fun status(now: LocalDateTime): CouponEventStatus = CouponEventStatus.of(period, soldOut = issuedQuantity >= totalQuantity, now)
```

`status` 컬럼이 있으면 "10시가 됐으니 UPCOMING → ONGOING 으로 바꿔라"는 배치가 필요하고, 배치가 늦으면 상태가 틀립니다. 계산하면 항상 맞습니다.

## 5. 시간을 인자로 받는다 — `Clock`

`status(now)`, `issue(now)` 처럼 **엔티티는 현재 시각을 직접 구하지 않습니다.** 대신 서비스가 `Clock` 빈에서 시각을 구해 넘깁니다.

[ClockConfig.kt](../src/main/kotlin/com/site/independent/config/ClockConfig.kt#L8-L25)

```kotlin
/**
 * LocalDateTime.now() 를 코드 곳곳에서 직접 부르면 "시간"을 테스트에서 통제할 수 없다.
 * Clock 을 빈으로 두고 LocalDateTime.now(clock) 으로 쓰면, 테스트에서 고정된 시각을 주입할 수 있다.
 *
 * 시간대는 서버 OS 설정에 맡기지 않고 서비스 시간대로 고정한다.
 * 이벤트 시각은 시간대 정보가 없는 LocalDateTime 이라, 서버가 UTC 로 돌면 "10시 시작"이 9시간 어긋나게 해석된다.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.system(SERVICE_ZONE)

    companion object {
        /** 서비스 시간대. 저장되는 모든 LocalDateTime 은 이 시간대 기준이다. */
        val SERVICE_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
```

얻는 것이 두 가지입니다.

1. **테스트**: 엔티티 테스트는 스프링 없이 `couponEvent.status(startAt.minusSeconds(1))` 처럼 원하는 시각을 넣습니다. ([CouponEventTest.kt](../src/test/kotlin/com/site/domain/coupon/entity/CouponEventTest.kt))
2. **시간대 사고 방지**: 운영 서버는 보통 UTC 로 돕니다. `LocalDateTime.now()` 는 서버 시간대를 쓰니 "10시 오픈" 이벤트가 한국 시각 19시에 열립니다. `Clock` 을 서울로 고정해서 막았습니다.

`createdAt`, `updatedAt` 을 찍는 JPA Auditing 도 같은 `Clock` 을 쓰게 했습니다.

[JpaAuditingConfig.kt](../src/main/kotlin/com/site/infrastructure/database/JpaAuditingConfig.kt#L11-L24)

```kotlin
/**
 * @EnableJpaAuditing 을 SiteApplication 에 붙이면 @WebMvcTest 같은 슬라이스 테스트에서
 * JPA 메타모델이 없어 컨텍스트가 깨진다. 별도 설정 클래스로 분리한다.
 *
 * createdAt / updatedAt 도 서비스 Clock 으로 찍는다. 기본 구현은 LocalDateTime.now() (= 서버 OS 시간대)라서
 * 서버가 UTC 면 이벤트 시각(서비스 시간대)과 기준이 섞인다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class JpaAuditingConfig {

    @Bean
    fun auditingDateTimeProvider(clock: Clock): DateTimeProvider = DateTimeProvider { Optional.of(LocalDateTime.now(clock)) }
}
```

테스트 JVM 은 일부러 UTC 로 돌립니다(`build.gradle.kts` 의 `-Duser.timezone=UTC`). 어딘가에서 서버 시간대에 기대는 코드가 생기면 [ServiceTimeZoneTest](../src/test/kotlin/com/site/independent/config/ServiceTimeZoneTest.kt) 가 깨집니다.

공통 시각 컬럼은 상위 클래스로 뺐습니다.

[BaseTimeEntity.kt](../src/main/kotlin/com/site/domain/common/entity/BaseTimeEntity.kt#L11-L24)

```kotlin
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseTimeEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set
}
```

## 6. enum 은 VARCHAR 로

[Member.kt](../src/main/kotlin/com/site/domain/member/entity/Member.kt#L46-L52)

```kotlin
    // Hibernate 6 는 MySQL 에서 @Enumerated(STRING) 을 네이티브 ENUM 컬럼으로 만든다.
    // 그러면 enum 값을 추가할 때마다 ALTER TABLE 이 필요하므로 VARCHAR 로 고정한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    var role: MemberRole = role
        protected set
```

- `EnumType.STRING`: 순서 번호(0, 1)가 아니라 이름(`"ADMIN"`)을 저장한다. 순서로 저장하면 enum 중간에 값을 끼워 넣는 순간 기존 데이터의 의미가 바뀐다.
- `@JdbcTypeCode(SqlTypes.VARCHAR)`: MySQL 의 `ENUM('USER','ADMIN')` 타입 대신 VARCHAR 를 쓰게 한다.

## 7. 테이블은 Flyway 가 만들고, Hibernate 는 검증만

[application.properties](../src/main/resources/application.properties)

```properties
spring.jpa.open-in-view=false

# 스키마는 Flyway(db/migration)가 만들고, Hibernate 는 엔티티와 스키마가 맞는지 검증만 한다
spring.jpa.hibernate.ddl-auto=validate
```

| 방식 | 문제 |
|---|---|
| `ddl-auto=update` (Hibernate 가 알아서) | 컬럼 삭제·이름 변경을 못 하고, 운영 DB 에 무엇이 언제 바뀌었는지 기록이 없다 |
| **Flyway + `validate`** | SQL 파일이 변경 이력이 된다. 엔티티와 테이블이 어긋나면 기동이 실패한다 |

마이그레이션 파일은 [db/migration](../src/main/resources/db/migration) 에 `V1__...`, `V2__...` 순서로 쌓입니다.

[V1__init_schema.sql](../src/main/resources/db/migration/V1__init_schema.sql#L1-L5)

```sql
-- M1(1~5단계)까지의 스키마.
-- 한 번 적용된 마이그레이션 파일은 고치지 않는다. 바꿀 것이 생기면 V2, V3 ... 새 파일로 추가한다.
--
-- enum 컬럼은 VARCHAR 로만 두고 CHECK 제약을 걸지 않는다.
-- (Hibernate 가 자동 생성하던 CHECK 제약은 enum 값을 추가할 때마다 DDL 을 바꿔야 해서 뺐다)
```

이미 데이터가 있는 테이블에 NOT NULL 컬럼을 추가하는 순서도 볼 만합니다.

[V4__add_coupon_validity_and_usage.sql](../src/main/resources/db/migration/V4__add_coupon_validity_and_usage.sql#L7-L20)

```sql
-- 발급된 쿠폰의 만료 시각, 사용 시각, 할인액, 낙관적 락 버전
ALTER TABLE issued_coupon
    ADD COLUMN expires_at      DATETIME(6) NULL AFTER member_id,
    ADD COLUMN used_at         DATETIME(6) NULL AFTER expires_at,
    ADD COLUMN discount_amount INT         NULL AFTER used_at,
    ADD COLUMN version         BIGINT      NOT NULL DEFAULT 0 AFTER discount_amount;

-- 이미 발급된 쿠폰은 발급 시각 + 30일로 채운 뒤 NOT NULL 로 바꾼다
UPDATE issued_coupon
SET expires_at = DATE_ADD(created_at, INTERVAL 30 DAY)
WHERE expires_at IS NULL;

ALTER TABLE issued_coupon
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL;
```

NULL 허용으로 추가 → 기존 행 채우기 → NOT NULL 로 변경. 처음부터 NOT NULL 로 추가하면 기존 행에 넣을 값이 없어 실패합니다.

테스트도 같은 Flyway 로 스키마를 만듭니다. 그래서 엔티티를 고치고 마이그레이션을 빼먹으면 테스트가 먼저 깨집니다.

## 8. 리포지토리 — 메서드 이름이 곧 쿼리

[IssuedCouponRepository.kt](../src/main/kotlin/com/site/domain/coupon/repository/IssuedCouponRepository.kt#L9-L20)

```kotlin
interface IssuedCouponRepository : JpaRepository<IssuedCoupon, Long> {

    fun existsByCouponEventIdAndMemberId(couponEventId: Long, memberId: Long): Boolean

    fun countByCouponEventId(couponEventId: Long): Long

    fun findByMemberId(memberId: Long, pageable: Pageable): Page<IssuedCoupon>

    /** 주어진 회원 중 이미 이 이벤트의 쿠폰을 받은 회원 (일괄 발급에서 한 번에 확인) */
    @Query("select c.memberId from IssuedCoupon c where c.couponEventId = :couponEventId and c.memberId in :memberIds")
    fun findIssuedMemberIds(couponEventId: Long, memberIds: Collection<Long>): List<Long>
}
```

- 구현 클래스가 없습니다. Spring Data 가 실행 중에 만들어 줍니다.
- `existsBy...And...` 처럼 메서드 이름을 규칙대로 지으면 쿼리가 만들어집니다.
- 이름으로 표현하기 어려우면 `@Query` 로 JPQL(테이블이 아니라 **엔티티와 프로퍼티 이름**으로 쓰는 SQL)을 적습니다. `:couponEventId` 는 파라미터 이름과 연결됩니다. ([2장](02-spring-meets-kotlin.md)의 `-java-parameters` 덕분에 `@Param` 이 필요 없습니다)

잠금과 일괄 수정도 리포지토리에 선언합니다.

[CouponEventRepository.kt](../src/main/kotlin/com/site/domain/coupon/repository/CouponEventRepository.kt#L16-L22)

```kotlin
    /**
     * SELECT ... FOR UPDATE — 트랜잭션이 끝날 때까지 다른 트랜잭션은 이 행을 잠그거나 수정할 수 없다.
     * 대기열 일괄 발급에서 "남은 수량 확인 → 여러 장 발급" 사이에 다른 워커가 끼어들지 못하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CouponEvent e where e.id = :id")
    fun findByIdForUpdate(id: Long): CouponEvent?
```

`@Modifying` UPDATE 쿼리(`increaseIssuedQuantity`)는 [7장](07-concurrency.md)에서 봅니다.

## 9. 영속성 컨텍스트와 변경 감지

트랜잭션 안에서 조회한 엔티티는 **영속성 컨텍스트**(1차 캐시)에 들어갑니다. Hibernate 는 처음 읽은 값을 기억해 두고, 커밋할 때 바뀐 필드가 있으면 UPDATE 를 만듭니다.

[CouponEventService.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponEventService.kt#L47-L66)

```kotlin
    /**
     * save() 를 부르지 않는다. 트랜잭션 안에서 조회한 엔티티의 값을 바꾸면
     * 커밋 시점에 JPA 가 변경을 감지해서(dirty checking) UPDATE 를 날린다.
     */
    @Transactional
    fun update(couponEventId: Long, command: CouponEventCommand): CouponEvent {
        val couponEvent = getCouponEvent(couponEventId)
        val now = now()

        couponEvent.update(
            name = command.name,
            discount = command.toDiscount(),
            period = command.toPeriod().also { it.requireNotEnded(now) },
            totalQuantity = command.totalQuantity,
            issueMode = command.issueMode,
            couponValidityDays = command.couponValidityDays,
            now = now,
        )
        return couponEvent
    }
```

`@Transactional` 이 없으면 영속성 컨텍스트가 메서드 밖까지 이어지지 않아 변경 감지가 일어나지 않습니다.

### 벌크 UPDATE 의 함정

[CouponEventRepository.kt](../src/main/kotlin/com/site/domain/coupon/repository/CouponEventRepository.kt#L28-L34)

```kotlin
    /**
     * "발급 가능할 때만 +1"을 UPDATE 한 문장으로 처리한다. 반환값은 바뀐 행 수(0 또는 1)다.
     * 발급 조건(방식·기간·수량)을 모두 조건절에 넣어서, 이 쿼리만으로도 규칙을 어기는 발급이 일어나지 않는다.
     *
     * - 영속성 컨텍스트를 거치지 않는 벌크 연산이라, 같은 트랜잭션에서 미리 읽어 둔 CouponEvent 가 있다면 그 값은 낡은 값이 된다.
     * - 엔티티 리스너를 거치지 않으므로 updatedAt(@LastModifiedDate)도 갱신되지 않는다.
     */
```

`@Modifying @Query("update ...")` 는 DB 에 바로 갑니다. 이미 메모리에 올라온 엔티티는 바뀐 줄 모릅니다. 그래서 이 쿼리를 쓰는 쪽은 미리 읽은 엔티티에서 **바뀌지 않는 값(유효기간)만** 씁니다.

```kotlin
// CouponIssueService.issueWithAtomicUpdate
        // UPDATE 는 영속성 컨텍스트를 거치지 않으므로 couponEvent 의 수량은 낡은 값이다. 유효기간만 쓴다
        return saveIssuedCoupon(couponEvent, memberId)
```

### OSIV 끄기

`spring.jpa.open-in-view=false` 는 영속성 컨텍스트를 HTTP 응답이 끝날 때까지 열어 두지 않게 합니다. 켜 두면 컨트롤러에서 지연 로딩이 몰래 일어나 쿼리가 어디서 나가는지 알기 어렵고, 요청 내내 DB 커넥션을 붙잡습니다. 대신 컨트롤러로 넘기기 전에 필요한 데이터를 서비스에서 다 꺼내야 합니다.

## 10. 연관관계 대신 ID — 그리고 N+1

[IssuedCoupon.kt](../src/main/kotlin/com/site/domain/coupon/entity/IssuedCoupon.kt#L16-L24)

```kotlin
/**
 * 회원에게 발급된 쿠폰. 발급 시각은 createdAt 이다.
 *
 * - 다른 애그리거트(CouponEvent, Member)는 @ManyToOne 객체 대신 ID 로 참조한다.
 * - (coupon_event_id, member_id) unique 제약이 "1인 1매"의 최후 방어선이다.
 * - 같은 쿠폰을 동시에 두 번 쓰는 것은 낙관적 락(@Version)으로 막는다.
 *   UPDATE ... WHERE version = ? 가 0건이면 다른 요청이 먼저 바꾼 것이므로 실패시킨다.
 *   충돌이 드문 작업(한 사람이 자기 쿠폰을 쓰는 일)이라 매번 잠그는 비관적 락보다 가볍다.
 */
```

`@ManyToOne val couponEvent: CouponEvent` 대신 `val couponEventId: Long` 을 둡니다. 객체 그래프가 끝없이 이어지지 않고, 발급 트랜잭션이 이벤트 엔티티를 함께 수정할 위험도 없습니다.

대신 "내 쿠폰 목록 + 각 쿠폰의 이벤트 이름"을 보여 줄 때 조심해야 합니다. 쿠폰마다 이벤트를 조회하면 쿠폰 20장에 쿼리 21번(1 + N)이 나갑니다.

[CouponWalletService.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponWalletService.kt#L27-L37)

```kotlin
    /**
     * 쿠폰과 그 이벤트 정보를 함께 돌려준다.
     * 쿠폰마다 이벤트를 따로 조회하면 쿠폰 수만큼 쿼리가 나간다(N+1). 이벤트 ID 를 모아 한 번에 조회한다.
     */
    fun getMyCoupons(memberId: Long, pageable: Pageable): Page<OwnedCoupon> {
        val coupons = issuedCouponRepository.findByMemberId(memberId, pageable)
        val events = couponEventRepository.findAllById(coupons.content.map { it.couponEventId }.toSet())
            .associateBy { it.id }

        return coupons.map { coupon -> OwnedCoupon(coupon, requireNotNull(events[coupon.couponEventId])) }
    }
```

쿼리 2번(쿠폰 페이지 + 이벤트 IN 조회)으로 끝납니다.

## 11. DB 제약을 마지막 방어선으로

애플리케이션 검사와 DB 저장 사이에는 항상 틈이 있습니다. 그 틈은 DB 제약이 막습니다.

[MemberService.kt](../src/main/kotlin/com/site/domain/member/service/MemberService.kt#L26-L45)

```kotlin
    ): Member {
        if (memberRepository.existsByEmail(email)) {
            throw ServiceException(ErrorCode.DUPLICATE_EMAIL)
        }

        val member = Member(
            email = email,
            password = passwordEncoder.encode(rawPassword),
            nickname = nickname,
            phoneNumber = phoneNumber,
        )

        // 같은 이메일 가입이 existsByEmail 과 저장 사이에 끼어들면 DB unique 제약이 최후의 방어선이 된다.
        // (길이 같은 다른 제약 위반은 요청 검증에서 먼저 걸러지므로, 여기서 나는 무결성 위반은 이메일 중복뿐이다)
        return try {
            memberRepository.saveAndFlush(member)
        } catch (e: DataIntegrityViolationException) {
            throw ServiceException(ErrorCode.DUPLICATE_EMAIL)
        }
    }
```

`save` 대신 `saveAndFlush` 를 쓰는 이유: `save` 는 INSERT 를 커밋 때까지 미룰 수 있어서 예외가 이 메서드 밖(트랜잭션 프록시)에서 터집니다. 그러면 catch 로 잡아 `DUPLICATE_EMAIL` 로 바꿔 줄 수 없습니다. 쿠폰 사용([CouponWalletService.use](../src/main/kotlin/com/site/domain/coupon/service/CouponWalletService.kt))의 낙관적 락 충돌도 같은 이유로 `saveAndFlush` 를 씁니다.

## 흔한 실수

| 실수 | 결과 | 대신 |
|---|---|---|
| 엔티티 필드를 `var` + public setter 로 둔다 | 어디서든 규칙을 우회해 값을 바꾼다 | `protected set` + 의미 있는 메서드 |
| 엔티티에서 `LocalDateTime.now()` 를 부른다 | 테스트로 시간을 통제할 수 없고, 서버 시간대에 따라 결과가 바뀐다 | `now` 를 인자로 받는다 |
| `ddl-auto=update` 로 운영한다 | 스키마 변경 이력이 없고 위험한 변경을 못 막는다 | Flyway + `validate` |
| 적용된 마이그레이션 파일을 고친다 | 체크섬이 달라 기동이 실패한다 | 새 버전 파일을 추가한다 |
| 벌크 UPDATE 뒤에 미리 읽은 엔티티 값을 믿는다 | 낡은 값을 쓴다 | 다시 읽거나, 바뀌지 않는 값만 쓴다 |
| 목록 + 연관 정보를 반복문 안에서 조회한다 | N+1 쿼리 | ID 를 모아 `findAllById` |

## 직접 해보기

1. `CouponEvent` 에 "이벤트 일시 중지(pause)" 기능을 넣는다면, 상태를 저장해야 할까요 계산해야 할까요? 필요한 컬럼과 마이그레이션 파일(`V5__...`)을 설계해 보세요.
2. `CouponWalletService.getMyCoupons` 를 쿠폰마다 `findByIdOrNull` 하는 방식으로 바꾸고, `logging.level.org.hibernate.SQL=debug` 로그에서 쿼리 수를 세어 보세요. (확인 후 되돌리세요)
3. `EventPeriod` 의 `init` 에 "종료가 지금 이후" 검사를 넣으면 어떤 테스트가 깨지나요? 왜 깨지나요?

다음 장: [5장. 웹 계층과 예외 처리](05-web-and-errors.md)
