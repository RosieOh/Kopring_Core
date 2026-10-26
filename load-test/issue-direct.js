// 즉시 발급(분산락 + 사전 검사): 모든 사용자가 같은 순간에 한 번씩 발급을 요청한다.
//   k6 run -e VUS=500 -e QUANTITY=100 load-test/issue-direct.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE_URL, authHeaders, createOngoingCouponEvent, errorCodeOf } from './lib/common.js';

const VUS = Number(__ENV.VUS || 500);
const QUANTITY = Number(__ENV.QUANTITY || 100);

const issued = new Counter('result_issued');
const soldOut = new Counter('result_sold_out');
const lockTimeout = new Counter('result_lock_timeout');
const otherFailure = new Counter('result_other');

// 409(소진), 503(락 대기 초과)은 정상적인 비즈니스 응답이라 실패율에서 뺀다
http.setResponseCallback(http.expectedStatuses(201, 409, 503));

export const options = {
  scenarios: {
    // 오픈 순간 몰림: 모든 VU 가 동시에 출발해 1번씩 요청한다
    rush: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' },
  },
  // 하위 지표를 요약에 보이게 하려고 걸어 두는 넉넉한 기준
  thresholds: {
    'http_req_duration{name:issue}': ['p(95)<10000'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { eventId: createOngoingCouponEvent({ name: `k6 즉시 발급 ${VUS}명`, totalQuantity: QUANTITY, issueMode: 'DIRECT' }) };
}

export default function (data) {
  const memberId = 1_000_000 + exec.vu.idInTest;
  const response = http.post(`${BASE_URL}/api/coupon-events/${data.eventId}/issue`, null, {
    ...authHeaders(memberId),
    tags: { name: 'issue' },
  });

  if (response.status === 201) issued.add(1);
  else if (errorCodeOf(response) === 'COUPON_SOLD_OUT') soldOut.add(1);
  else if (errorCodeOf(response) === 'LOCK_ACQUISITION_FAILED') lockTimeout.add(1);
  else otherFailure.add(1);
}
