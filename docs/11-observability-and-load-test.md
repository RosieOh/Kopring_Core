# 11장. 관측과 부하 테스트 — "빨라졌다"를 숫자로

**한 줄 요약**: 코드에서 지표를 남기고(Micrometer), 1초마다 모으고(Prometheus), 그래프로 봅니다(Grafana). k6 로 오픈 순간을 재현해 같은 조건에서 방식끼리 비교하고, 숫자가 **왜** 그렇게 나왔는지 서버 지표로 설명합니다.

## 1. 왜 지표가 필요한가

"분산락보다 원자적 UPDATE 가 빠르다"는 말만으로는 부족합니다. 얼마나? 어떤 조건에서? 대가는? [7장](07-concurrency.md)의 결론 "원자적 UPDATE 가 2.4~2.6배 빠르지만 DB 커넥션 대기가 110까지 쌓인다"는 두 종류의 숫자가 있어야 나옵니다.

| 숫자 | 어디서 | 예 |
|---|---|---|
| 사용자가 느낀 것 | k6 (부하 도구) | 응답 중앙값, p95, 실패 수 |
| 서버 안에서 일어난 것 | Micrometer → Prometheus | DB 커넥션 대기, Tomcat 스레드, 락 대기 시간 |

앞의 것만 있으면 "느려졌다"까지만 알고, 뒤의 것이 있어야 "**왜** 느려졌나"를 압니다.

## 2. Micrometer — 지표를 남기는 코드

Micrometer 는 지표용 파사드입니다. 로그의 SLF4J 처럼, 코드는 Micrometer 에만 의존하고 실제 저장소(Prometheus 등)는 의존성으로 고릅니다. 이 프로젝트는 `micrometer-registry-prometheus` 를 씁니다.

### 카운터 — 몇 번 일어났나

[CouponIssueMetrics.kt](../src/main/kotlin/com/site/domain/coupon/service/CouponIssueMetrics.kt#L8-L31)

```kotlin
/**
 * 발급 요청 결과를 센다. Prometheus 에서는 coupon_issue_requests_total{mode, result} 로 보인다.
 *
 * Micrometer 는 지표용 파사드(로깅의 SLF4J 같은 것)라서 domain 에서 직접 써도 특정 모니터링 시스템에 묶이지 않는다.
 * result 태그 값은 enum 이름이라 종류가 정해져 있다. (태그 값 종류가 무한히 늘면 Prometheus 메모리가 터진다)
 */
@Component
class CouponIssueMetrics(
    private val meterRegistry: MeterRegistry,
) {

    fun recordDirectIssued() = record(mode = "direct", result = "issued")

    fun recordDirectFailed(errorCode: ErrorCode) = record(mode = "direct", result = resultOf(errorCode))

    fun recordQueue(result: CouponQueueResult, count: Long = 1) = record(mode = "queue", result = result.name.lowercase(), count = count)

    private fun record(
        mode: String,
        result: String,
        count: Long = 1,
    ) {
        meterRegistry.counter(NAME, "mode", mode, "result", result).increment(count.toDouble())
    }
```

**태그(라벨)** 로 같은 지표를 나눠 셉니다. `coupon_issue_requests_total{mode="direct", result="sold_out"}` 처럼요.

태그에서 가장 중요한 규칙은 **값의 종류가 정해져 있어야 한다**는 것입니다(카디널리티). 태그 조합마다 Prometheus 가 시계열 하나를 따로 저장합니다. `memberId` 를 태그에 넣으면 회원 수만큼 시계열이 생겨 메모리가 터집니다. 여기서는 결과를 enum 이름으로만 씁니다.

즉시 발급과 대기열의 결과 이름을 같게(`sold_out`, `issued` ...) 맞춘 것도 의도입니다. 두 방식을 대시보드에서 나란히 비교할 수 있습니다.

### 타이머 + 히스토그램 — 얼마나 걸렸나

[DistributedLockAspect.kt](../src/main/kotlin/com/site/infrastructure/redis/lock/DistributedLockAspect.kt#L64-L80)

```kotlin
    /**
     * 락을 얻기까지 기다린 시간. Prometheus 에서는 lock_wait_seconds_bucket{lock, result} 로 보인다.
     * 태그에는 락 이름 전체(이벤트 ID 포함)가 아니라 종류만 넣는다. ('coupon-event:' + #id → coupon-event)
     */
    private fun recordWait(
        key: String,
        acquired: Boolean,
        nanos: Long,
    ) {
        Timer.builder("lock.wait")
            .description("분산락을 얻기까지 기다린 시간")
            .tag("lock", key.substringAfter("'").substringBefore(":"))
            .tag("result", if (acquired) "acquired" else "timeout")
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS)
    }
```

- 락 이름 전체(`coupon-event:7`)가 아니라 **종류**(`coupon-event`)만 태그에 넣습니다. 이벤트 ID 를 넣으면 이벤트 수만큼 시계열이 생깁니다.
- `publishPercentileHistogram()`: 시간을 구간(bucket)별로 세어 내보냅니다. Prometheus 가 여러 서버의 값을 합쳐 p95 를 계산할 수 있습니다. 서버 안에서 p95 를 계산해 내보내면 서버끼리 합칠 수 없습니다.

### 게이지 — 지금 몇 개인가

[NotificationOutboxMetrics.kt](../src/main/kotlin/com/site/domain/notification/service/NotificationOutboxMetrics.kt#L20-L26)

```kotlin
    init {
        // 게이지 값은 Prometheus 가 가져갈 때(scrape)마다 계산된다. (status, next_attempt_at) 인덱스를 타는 count 쿼리다.
        Gauge.builder("notification.outbox.pending", outboxRepository) { it.countByStatus(OutboxStatus.PENDING).toDouble() }
            .description("아직 보내지 못한 알림 수")
            .strongReference(true)
            .register(meterRegistry)
    }
```

카운터는 계속 늘기만 하고, 게이지는 오르내리는 현재 값입니다. "아직 못 보낸 알림"이 계속 늘면 발송이 막힌 것입니다.

대기열 크기처럼 **대상이 그때그때 바뀌는** 게이지는 `MultiGauge` 로 행 전체를 덮어씁니다.

[CouponQueueMetrics.kt](../src/main/kotlin/com/site/domain/coupon/queue/CouponQueueMetrics.kt#L9-L33)

```kotlin
/**
 * 이벤트별 대기열 크기. Prometheus 에서는 coupon_queue_size{event_id, state="waiting|processing"} 로 보인다.
 *
 * 진행 중인 이벤트는 그때그때 달라서 게이지를 미리 등록해 둘 수 없다. MultiGauge 에 "지금 있는 행"을 통째로 덮어쓰면
 * 새 이벤트는 추가되고, 끝난 이벤트는 지표에서 사라진다.
 */
@Component
class CouponQueueMetrics(
    meterRegistry: MeterRegistry,
    private val couponQueueStore: CouponQueueStore,
) {

    private val queueSize = MultiGauge.builder(NAME)
        .description("선착순 대기열 인원")
        .register(meterRegistry)

    fun refresh(activeEventIds: Set<Long>) {
        val rows = activeEventIds.flatMap { couponEventId ->
            listOf(
                row(couponEventId, "waiting", couponQueueStore.waitingCount(couponEventId)),
                row(couponEventId, "processing", couponQueueStore.processingCount(couponEventId)),
            )
        }
        queueSize.register(rows, true)
    }
```

여기는 `event_id` 를 태그로 씁니다. 동시에 진행되는 대기열 이벤트는 몇 개뿐이고, 끝난 이벤트는 지표에서 빠지므로 시계열이 무한히 늘지 않습니다. 같은 "ID 태그"라도 **동시에 몇 개가 살아 있나**로 판단합니다.

### 스프링이 알아서 주는 지표

[application.properties](../src/main/resources/application.properties)

```properties
# 지표: 모든 지표에 애플리케이션 이름 태그를 붙이고, HTTP 응답 시간은 히스토그램으로 내보내 Prometheus 에서 p95 를 계산한다
management.metrics.tags.application=kopring-core
management.metrics.distribution.percentiles-histogram.http.server.requests=true
# Tomcat MBean 등록은 끈다 (기본값). 켜면 연결마다 MBean 을 등록·해제하는데, 그 코드가 synchronized 안에서 JMX 서버를 기다려
# 가상 스레드가 운영체제 스레드에 고정(pinning)된다. 가상 스레드에서는 Tomcat 스레드 풀 지표도 의미가 없어서,
# 요청이 얼마나 몰렸는지는 처리 중인 요청 수(http_server_requests_active_seconds_gcount)로 본다.
```

| 지표 | 무엇을 보나 |
|---|---|
| `http_server_requests_seconds` | API 별 응답 시간 |
| `hikaricp_connections_pending` | DB 커넥션을 기다리는 요청 수 |
| `http_server_requests_active_seconds_gcount` | 지금 처리 중인 요청 수 |

M4 까지는 Tomcat 스레드 지표(`tomcat_threads_busy_threads`, 최대 200)를 봤습니다. 이 지표를 내려면 `server.tomcat.mbeanregistry.enabled=true` 가 필요한데, M5 에서 가상 스레드를 켜자 바로 이 설정이 **앱 전체를 멈추게** 했습니다. 그 이야기는 [13장](13-virtual-threads-and-backpressure.md)에 있습니다.

`_gcount` 라는 이름이 붙는 이유: 위의 히스토그램 설정이 접두어(`http.server.requests`)로 `http.server.requests.active` 에도 걸려서, 처리 중인 요청을 세는 LongTaskTimer 가 "게이지 히스토그램"으로 나갑니다. 그 개수가 `_gcount` 입니다. 지표 이름은 추측하지 말고 `/actuator/prometheus` 출력에서 확인하세요.

지표는 `/actuator/prometheus` 로 나갑니다. 로컬에서만 노출하고, 운영 프로필은 health 만 엽니다. ([SecurityConfig](../src/main/kotlin/com/site/infrastructure/security/SecurityConfig.kt) 주석)

## 3. Prometheus 와 Grafana

```bash
docker compose --profile monitoring up -d
# Prometheus http://localhost:9090, Grafana http://localhost:3000
```

[prometheus.yml](../monitoring/prometheus/prometheus.yml)

```yaml
# 로컬에서 ./gradlew bootRun 으로 띄운 앱(호스트 8080)의 지표를 1초마다 가져온다 (몇 초짜리 부하 테스트도 놓치지 않게)
global:
  scrape_interval: 1s
  evaluation_interval: 1s
```

Prometheus 는 앱에 **가서 가져갑니다(pull).** 흔히 15초~1분 간격으로 모으지만, 부하 테스트가 몇 초 만에 끝나서 1초로 줄였습니다.

Grafana 대시보드("Kopring Core — 선착순 쿠폰")는 [monitoring/grafana](../monitoring/grafana) 에 파일로 들어 있어, 컨테이너를 띄우면 자동으로 만들어집니다.

부하 테스트 결과를 표로 만드는 스크립트가 쓰는 PromQL 입니다.

[summarize.py](../load-test/summarize.py#L18-L33)

```python
def server_metrics(start: int, end: int) -> dict:
    # 실행 구간 + 지표 반영 여유(3초)
    window = end - start + 3
    at = end + 3
    return {
        "db_pending_max": prom(f"max_over_time(sum(hikaricp_connections_pending{{{APP}}})[{window}s:1s])", at),
        # 가상 스레드에는 Tomcat 스레드 풀 지표가 없어서, 스레드 방식과 상관없는 "처리 중인 요청 수"로 본다
        "inflight_max": prom(f"max_over_time(sum(http_server_requests_active_seconds_gcount{{{APP}}})[{window}s:1s])", at),
        "lock_wait_p95": prom(
            f"histogram_quantile(0.95, sum by (le) (increase(lock_wait_seconds_bucket{{{APP}}}[{window}s])))", at
        ),
        "bulkhead_wait_p95": prom(
            f"histogram_quantile(0.95, sum by (le) (increase(coupon_issue_bulkhead_wait_seconds_bucket{{{APP}}}[{window}s])))", at
        ),
        "queue_waiting_max": prom(f'max_over_time(sum(coupon_queue_size{{{APP}, state="waiting"}})[{window}s:1s])', at),
    }
```

| PromQL | 뜻 |
|---|---|
| `max_over_time(x[60s:1s])` | 지난 60초 동안 1초 간격 값 중 최대 |
| `increase(x_bucket[60s])` | 60초 동안 구간별로 늘어난 횟수 |
| `histogram_quantile(0.95, sum by (le) (...))` | 구간별 횟수로 p95 계산 |

## 4. k6 — 오픈 순간 재현하기

[issue-direct.js](../load-test/issue-direct.js#L23-L41)

```javascript
export const options = {
  scenarios: {
    // 오픈 순간 몰림: 모든 VU 가 동시에 출발해 1번씩 요청한다
    rush: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m', exec: 'rush' },
    // 다른 사용자가 같은 시간에 이벤트 목록을 본다 (DB 를 읽는 다른 API)
    ...(BROWSE_RATE > 0
      ? {
          browse: {
            executor: 'constant-arrival-rate',
            rate: BROWSE_RATE,
            timeUnit: '1s',
            duration: `${BROWSE_SECONDS}s`,
            preAllocatedVUs: BROWSE_RATE,
            maxVUs: BROWSE_RATE * 20,
            exec: 'browse',
          },
        }
      : {}),
  },
```

선착순 쿠폰의 부하는 "초당 N건이 꾸준히"가 아니라 "**정각에 한꺼번에**"입니다. 가상 사용자(VU) 전원이 동시에 출발해 한 번씩 요청하게 했습니다.

`browse` 시나리오(M5)는 `BROWSE_RATE` 를 주면 켜집니다. 발급이 몰리는 동안 다른 사용자가 이벤트 목록을 초당 N번 보게 해서, **몰림이 다른 기능을 얼마나 느리게 하는지** 잽니다. 두 시나리오가 함께 돌고, 결과는 `name` 태그(`issue`, `browse`)로 나눠 봅니다.

토큰은 k6 가 직접 서명합니다.

[lib/common.js](../load-test/lib/common.js#L14-L20)

```javascript
/**
 * 회원 ID 로 access token 을 직접 서명한다.
 *
 * 회원 수천 명을 가입·로그인시키면 bcrypt 비용 때문에 준비에만 수 분이 걸리고, 그 부하가 측정에도 섞인다.
 * 서버(JwtTokenProvider)는 서명·만료만 검증하고, 발급은 회원 존재 여부를 따지지 않으므로 토큰만 있으면 된다.
 * 알고리즘은 jjwt 와 같이 키 길이로 정한다. (32~47바이트 HS256, 48~63바이트 HS384, 64바이트 이상 HS512)
 */
```

**측정하려는 것만 측정하기**: 준비 작업(가입·로그인)이 측정에 섞이지 않게 했습니다. 이벤트 생성 같은 준비 요청은 `name: 'setup'` 태그로 구분해 결과에서 뺍니다.

실행은 스크립트 하나로 합니다.

```bash
./load-test/run-comparison.sh                                     # 100·500·1000·2000명, 쿠폰 100장
VUS_LIST="2000 5000" QUANTITY=1000 ./load-test/run-comparison.sh  # 조건 바꾸기
SCENARIOS=direct LABEL=lock ./load-test/run-comparison.sh         # 한 방식만, 결과는 results/lock/
```

## 5. 숫자 읽는 법

### 평균 대신 중앙값과 p95

| 통계 | 뜻 | 왜 |
|---|---|---|
| 중앙값 (p50) | 절반은 이보다 빠르다 | 평균은 느린 몇 명에 끌려간다 |
| p95 | 95% 는 이보다 빠르다 | "운 나쁜 사용자"의 경험 |
| 최대 | 가장 느린 한 명 | 타임아웃에 얼마나 가까운지 |

### 정합성부터 확인

빠른 것보다 **맞는 것**이 먼저입니다. 모든 실행 후 DB 에서 "발급 수량 = 발급 내역 건수 = min(쿠폰 수, 사용자 수)"를 확인했습니다. 대기열 M3 의 5,000명 실행처럼 클라이언트가 시간 초과로 포기한 경우에도, 서버는 결국 1,000장을 정확히 나눠 줬습니다.

### 같은 조건에서 나란히

[load-test/README.md](../load-test/README.md) 의 "방법" 절:

- 개선 전 숫자는 기록해 둔 옛 값이 아니라 **M3 코드를 같은 날 같은 기계에서 따로 띄워** 다시 쟀다.
- 실행마다 워밍업(200명)을 먼저 돌려 JIT 영향을 뺐다.
- 같은 조건이라도 실행마다 10~15% 흔들린다. (대기열 M3 1,000장 · 2,000명을 두 번 재서 14.23s, 12.19s)

차이가 2배 이상이면 흔들림을 넘는 확실한 차이고, 10% 차이는 다시 재 봐야 합니다.

## 6. 사례 — M3 에서 M4 로

M3 에서 처음 잰 결과가 병목 세 개를 가리켰고, M4 에서 각각 고친 뒤 다시 쟀습니다.

| 병목 (M3 에서 본 것) | 서버 지표가 말해 준 원인 | M4 의 처리 | 결과 |
|---|---|---|---|
| 대기열: 1,000장에 12초 | 워커가 회원마다 트랜잭션 (커밋 1,000번) | 50명씩 한 트랜잭션, 줄이 남으면 이어서 처리 | 12.19s → **1.86s** |
| 대기열: 요청 38,412건 | 모두 0.5초마다 조회 | 서버가 조회 간격을 알려 줌, SSE | 38,412 → **6,163건** |
| 즉시 발급: 커넥션 대기 75 | 사전 검사가 요청마다 DB 를 읽음 | 사전 검사를 메모리 캐시로, 원자적 UPDATE 를 기본으로 | 4.77s → **1.92s** |

(1,000장 · 2,000명 기준, 중앙값)

숫자를 읽고 설명하는 연습으로, README 의 해석 하나를 따라가 봅니다.

> 원자적 UPDATE 의 대가는 DB 커넥션입니다. 행 잠금을 기다리는 요청은 DB 커넥션을 쥔 채 기다립니다. 1,000장 · 2,000명에서 커넥션을 얻으려고 기다린 요청이 110 까지 쌓였습니다(분산락은 기다림이 Redis 에서 일어나 0).

응답 시간(k6)만 봤다면 "원자적 UPDATE 가 무조건 낫다"고 결론 냈을 겁니다. 서버 지표(`hikaricp_connections_pending`)가 대가를 보여 줬고, 그래서 방식을 설정으로 남기는 결정을 했습니다.

**나빠진 곳도 기록합니다.** 쿠폰 100장 · 2,000명에서는 대기열 p95 가 1.87s → 2.38s 로 늘었습니다. 조회 간격 힌트가 남은 쿠폰 수를 모르기 때문입니다. ([9장](09-redis-queue.md)) 좋아진 숫자만 고르면 다음 개선 거리를 잃습니다.

### 측정의 한계를 같이 적는다

- k6·앱·DB 가 한 기계에서 자원을 나눠 썼다. 절대값보다 **상대 비교**다.
- 서버 지표는 1초 간격 표본이라, 1초 안에 끝난 실행의 최대치는 놓칠 수 있다.
- `bootRun` 은 JIT 를 C1 까지만 써서(`-XX:TieredStopAtLevel=1`) 운영(jar 실행)보다 느리다. 모든 실행이 같은 조건이다.

한계를 적어 두면, 읽는 사람이 숫자를 과하게 믿지 않습니다.

## 흔한 실수

| 실수 | 결과 | 대신 |
|---|---|---|
| 태그에 회원 ID·요청 ID | 시계열 폭발, Prometheus 메모리 부족 | 종류가 정해진 값만 |
| 서버에서 p95 를 계산해 내보낸다 | 서버 여러 대의 값을 합칠 수 없다 | 히스토그램을 내보내고 Prometheus 에서 계산 |
| 평균 응답 시간만 본다 | 느린 사용자가 가려진다 | 중앙값 + p95 |
| 개선 후만 잰다, 개선 전은 옛 기록 | 조건이 달라 비교가 안 된다 | 같은 날 같은 기계에서 나란히 |
| 한 번 재고 결론 | 10~15% 흔들림을 개선으로 착각 | 큰 차이만 믿고, 작은 차이는 다시 잰다 |
| 준비 작업(가입·로그인)을 측정에 섞는다 | 측정 대상이 아닌 비용이 섞인다 | 토큰 직접 서명, setup 태그 분리 |
| 응답 시간만 보고 결론 | 대가(커넥션·스레드)를 못 본다 | 서버 지표를 같이 |

## 직접 해보기

1. `CouponIssueMetrics` 에 이벤트 ID 를 태그로 추가하면 어떤 문제가 생길까요? 대기열 크기 게이지(`event_id` 태그)와는 무엇이 다른가요?
2. 로컬에서 `docker compose --profile monitoring up -d` 와 `./gradlew bootRun` 을 띄우고, `VUS_LIST="500" ./load-test/run-comparison.sh` 를 돌리면서 Grafana 에서 DB 커넥션 대기 그래프를 관찰해 보세요.
3. [load-test/README.md](../load-test/README.md) 의 결과 표에서, 즉시 발급이 대기열보다 빠른 조건과 느린 조건을 하나씩 찾고 이유를 설명해 보세요.

다음 장: [12장. 다음 공부와 연습 문제](12-next-steps-and-exercises.md)
