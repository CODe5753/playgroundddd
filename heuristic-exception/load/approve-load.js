import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const vus = Number(__ENV.VUS || 60);
const duration = __ENV.DURATION || '60s';

export const options = {
  stages: [
    { duration: '10s', target: Math.min(20, vus) },
    { duration: '20s', target: vus },
    { duration: duration, target: vus },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.2'],
  },
};

export default function () {
  const approvalId = `APP-${__VU}-${__ITER}-${Date.now()}`;
  const payload = JSON.stringify({
    approvalId,
    amount: 100.00,
    phoneNumber: '010-1234-5678',
    message: 'load-test',
  });

  const res = http.post(`${baseUrl}/approve`, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '5s',
  });

  check(res, {
    'status is 200/500': (r) => r.status === 200 || r.status === 500,
  });

  sleep(0.1);
}
