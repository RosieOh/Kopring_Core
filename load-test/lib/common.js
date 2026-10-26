// 부하 테스트 공통 모듈: 설정값, 토큰 서명, 이벤트 생성
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// application-local.properties 의 로컬 전용 JWT 키 (운영 키를 여기에 넣지 않는다)
const JWT_SECRET = __ENV.JWT_SECRET || 'a29wcmluZy1jb3JlLWxvY2FsLWRldmVsb3BtZW50LXNlY3JldC1rZXktMjAyNg==';

// 서버가 이벤트 시각을 해석하는 서비스 시간대 (Asia/Seoul, UTC+9)
const SERVICE_OFFSET_MILLIS = 9 * 60 * 60 * 1000;

/**
 * 회원 ID 로 access token 을 직접 서명한다.
 *
 * 회원 수천 명을 가입·로그인시키면 bcrypt 비용 때문에 준비에만 수 분이 걸리고, 그 부하가 측정에도 섞인다.
 * 서버(JwtTokenProvider)는 서명·만료만 검증하고, 발급은 회원 존재 여부를 따지지 않으므로 토큰만 있으면 된다.
 * 알고리즘은 jjwt 와 같이 키 길이로 정한다. (32~47바이트 HS256, 48~63바이트 HS384, 64바이트 이상 HS512)
 */
export function accessTokenFor(memberId) {
  const key = encoding.b64decode(JWT_SECRET);
  const alg = key.byteLength >= 64 ? 'HS512' : key.byteLength >= 48 ? 'HS384' : 'HS256';
  const hash = { HS256: 'sha256', HS384: 'sha384', HS512: 'sha512' }[alg];
  const now = Math.floor(Date.now() / 1000);

  const header = encoding.b64encode(JSON.stringify({ alg }), 'rawurl');
  const payload = encoding.b64encode(
    JSON.stringify({ jti: `load-${memberId}-${now}`, sub: String(memberId), role: 'USER', iat: now, exp: now + 3600 }),
    'rawurl',
  );
  const signature = crypto.hmac(hash, key, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

export function authHeaders(memberId) {
  return { headers: { Authorization: `Bearer ${accessTokenFor(memberId)}` } };
}

// 준비 단계 요청은 측정 대상이 아니다. 시나리오별 기대 응답 코드와 상관없이 성공으로 보고, 이름으로 구분한다.
const SETUP_PARAMS = { tags: { name: 'setup' }, responseCallback: http.expectedStatuses(200, 201) };

/** 관리자로 로그인해서 지금 진행 중인 쿠폰 이벤트를 하나 만든다. (setup 단계에서 한 번) */
export function createOngoingCouponEvent({ name, totalQuantity, issueMode }) {
  const login = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL || 'admin@kopring.local', password: __ENV.ADMIN_PASSWORD || 'admin1234!' }),
    { headers: { 'Content-Type': 'application/json' }, ...SETUP_PARAMS },
  );
  if (login.status !== 200) throw new Error(`관리자 로그인 실패: ${login.status} ${login.body}`);
  const adminToken = login.json('data.accessToken');

  const response = http.post(
    `${BASE_URL}/api/admin/coupon-events`,
    JSON.stringify({
      name,
      discountType: 'FIXED_AMOUNT',
      discountValue: 1000,
      totalQuantity,
      startAt: serviceDateTime(-60),
      endAt: serviceDateTime(60 * 60),
      issueMode,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` }, ...SETUP_PARAMS },
  );
  if (response.status !== 201) throw new Error(`이벤트 생성 실패: ${response.status} ${response.body}`);
  return response.json('data.id');
}

/** 지금 + offsetSeconds 를 서비스 시간대의 LocalDateTime 문자열로 (예: 2026-10-26T10:00:00) */
function serviceDateTime(offsetSeconds) {
  return new Date(Date.now() + SERVICE_OFFSET_MILLIS + offsetSeconds * 1000).toISOString().slice(0, 19);
}

/** 응답 본문의 에러 코드 (실패 응답이 아니면 null) */
export function errorCodeOf(response) {
  try {
    return response.json('code');
  } catch (e) {
    return null;
  }
}
