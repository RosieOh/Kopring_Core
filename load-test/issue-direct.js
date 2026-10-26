// 즉시 발급: 모든 사용자가 같은 순간에 한 번씩 발급을 요청한다.
//   k6 run -e VUS=500 -e QUANTITY=100 load-test/issue-direct.js
//   BROWSE_RATE=50 → 발급이 몰리는 동안 다른 API(이벤트 목록 조회)를 초당 50번 불러, 몰림이 다른 기능에 주는 영향을 잰다
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE_URL, authHeaders, createOngoingCouponEvent, errorCodeOf } from './lib/common.js';

const VUS = Number(__ENV.VUS || 500);
const QUANTITY = Number(__ENV.QUANTITY || 100);
const BROWSE_RATE = Number(__ENV.BROWSE_RATE || 0);
const BROWSE_SECONDS = Number(__ENV.BROWSE_SECONDS || 10);

const issued = new Counter('result_issued');
const soldOut = new Counter('result_sold_out');
const lockTimeout = new Counter('result_lock_timeout');
const busy = new Counter('result_busy');
const otherFailure = new Counter('result_other');

// 409(소진), 503(락·발급 자리 대기 초과)은 정상적인 비즈니스 응답이라 실패율에서 뺀다
http.setResponseCallback(http.expectedStatuses(200, 201, 409, 503));

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
  // 하위 지표를 요약에 보이게 하려고 걸어 두는 넉넉한 기준
  thresholds: {
    'http_req_duration{name:issue}': ['p(95)<10000'],
    'http_req_duration{name:browse}': ['p(95)<10000'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { eventId: createOngoingCouponEvent({ name: `k6 즉시 발급 ${VUS}명`, totalQuantity: QUANTITY, issueMode: 'DIRECT' }) };
}

export function rush(data) {
  const memberId = 1_000_000 + exec.vu.idInTest;
  const response = http.post(`${BASE_URL}/api/coupon-events/${data.eventId}/issue`, null, {
    ...authHeaders(memberId),
    tags: { name: 'issue' },
  });

  if (response.status === 201) issued.add(1);
  else if (errorCodeOf(response) === 'COUPON_SOLD_OUT') soldOut.add(1);
  else if (errorCodeOf(response) === 'LOCK_ACQUISITION_FAILED') lockTimeout.add(1);
  else if (errorCodeOf(response) === 'COUPON_ISSUE_BUSY') busy.add(1);
  else otherFailure.add(1);
}

export function browse() {
  http.get(`${BASE_URL}/api/coupon-events?size=20`, { ...authHeaders(1), tags: { name: 'browse' } });
}
