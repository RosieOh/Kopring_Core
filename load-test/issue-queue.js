// 대기열: 모든 사용자가 같은 순간에 줄을 서고, 0.5초마다 내 상태를 조회해 결과를 받는다.
//   k6 run -e VUS=500 -e QUANTITY=100 load-test/issue-queue.js
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, authHeaders, createOngoingCouponEvent, errorCodeOf } from './lib/common.js';

const VUS = Number(__ENV.VUS || 500);
const QUANTITY = Number(__ENV.QUANTITY || 100);
const POLL_INTERVAL_SECONDS = Number(__ENV.POLL_INTERVAL || 0.5);
const RESULT_TIMEOUT_MILLIS = 60_000;

const issued = new Counter('result_issued');
const soldOut = new Counter('result_sold_out');
const otherFailure = new Counter('result_other');
const notDecided = new Counter('result_timeout');
// 줄을 선 순간부터 결과(DONE)를 받기까지 걸린 시간 — 사용자가 실제로 기다린 시간
const timeToResult = new Trend('time_to_result', true);

http.setResponseCallback(http.expectedStatuses(200, 202, 409));

export const options = {
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '3m' },
  },
  thresholds: {
    'http_req_duration{name:enqueue}': ['p(95)<10000'],
    'http_req_duration{name:status}': ['p(95)<10000'],
    time_to_result: ['p(95)<60000'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { eventId: createOngoingCouponEvent({ name: `k6 대기열 ${VUS}명`, totalQuantity: QUANTITY, issueMode: 'QUEUE' }) };
}

export default function (data) {
  const memberId = 2_000_000 + exec.vu.idInTest;
  const params = authHeaders(memberId);
  const startedAt = Date.now();

  const enqueue = http.post(`${BASE_URL}/api/coupon-events/${data.eventId}/queue`, null, { ...params, tags: { name: 'enqueue' } });
  if (enqueue.status === 409 && errorCodeOf(enqueue) === 'COUPON_SOLD_OUT') {
    // 이미 마감돼서 줄도 못 선 경우 — 바로 결과를 받은 셈이다
    soldOut.add(1);
    timeToResult.add(Date.now() - startedAt);
    return;
  }
  if (enqueue.status !== 202) {
    otherFailure.add(1);
    return;
  }

  while (Date.now() - startedAt < RESULT_TIMEOUT_MILLIS) {
    sleep(POLL_INTERVAL_SECONDS);
    const status = http.get(`${BASE_URL}/api/coupon-events/${data.eventId}/queue/me`, { ...params, tags: { name: 'status' } });
    if (status.status !== 200 || status.json('data.state') !== 'DONE') continue;

    timeToResult.add(Date.now() - startedAt);
    const result = status.json('data.result');
    if (result === 'ISSUED') issued.add(1);
    else if (result === 'SOLD_OUT') soldOut.add(1);
    else otherFailure.add(1);
    return;
  }
  notDecided.add(1);
}
