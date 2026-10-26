// 대기열: 모든 사용자가 같은 순간에 줄을 서고, 결과가 나올 때까지 내 상태를 조회한다.
//   k6 run -e VUS=500 -e QUANTITY=100 load-test/issue-queue.js
//   POLL_MODE=hint  (기본) 서버가 알려준 retryAfterMillis 만큼 기다렸다가 조회
//   POLL_MODE=fixed 순번과 상관없이 POLL_INTERVAL(기본 0.5초)마다 조회 (개선 전 방식)
//   EVENT_ID=12     이벤트를 새로 만들지 않고 이미 있는 대기열 이벤트에 줄을 선다
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, authHeaders, createOngoingCouponEvent, errorCodeOf } from './lib/common.js';

const VUS = Number(__ENV.VUS || 500);
const QUANTITY = Number(__ENV.QUANTITY || 100);
const POLL_INTERVAL_SECONDS = Number(__ENV.POLL_INTERVAL || 0.5);
const POLL_MODE = __ENV.POLL_MODE || 'hint';
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
  if (__ENV.EVENT_ID) return { eventId: Number(__ENV.EVENT_ID) };
  return { eventId: createOngoingCouponEvent({ name: `k6 대기열 ${VUS}명`, totalQuantity: QUANTITY, issueMode: 'QUEUE' }) };
}

/** 다음 조회까지 기다릴 시간(초). hint 모드면 서버가 응답에 담아 준 값을 따른다 */
function nextPollSeconds(response) {
  if (POLL_MODE !== 'hint') return POLL_INTERVAL_SECONDS;
  const hint = response.json('data.retryAfterMillis');
  return hint ? hint / 1000 : POLL_INTERVAL_SECONDS;
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

  let waitSeconds = nextPollSeconds(enqueue);
  while (Date.now() - startedAt < RESULT_TIMEOUT_MILLIS) {
    sleep(waitSeconds);
    const status = http.get(`${BASE_URL}/api/coupon-events/${data.eventId}/queue/me`, { ...params, tags: { name: 'status' } });
    if (status.status !== 200) continue;
    if (status.json('data.state') !== 'DONE') {
      waitSeconds = nextPollSeconds(status);
      continue;
    }

    timeToResult.add(Date.now() - startedAt);
    const result = status.json('data.result');
    if (result === 'ISSUED') issued.add(1);
    else if (result === 'SOLD_OUT') soldOut.add(1);
    else otherFailure.add(1);
    return;
  }
  notDecided.add(1);
}
