import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { getToken } from './auth.js';
import { randomPrompt } from './prompts.js';

const BASE = 'http://localhost:8080';

const accepted202 = new Counter('post_202');
const rateLimited429 = new Counter('post_429');
const serverErrors5xx = new Counter('post_5xx');
const clientErrors4xx = new Counter('post_4xx');
const otherStatuses = new Counter('post_other');
const postDuration = new Trend('post_duration_ms', true);

export const options = {
  scenarios: {
    throughput: {
      executor: 'constant-vus',
      vus: 50,
      duration: '2m',
    },
  },
  thresholds: {
    'http_req_duration{name:POST /api/v1/generations}': ['p(95)<2000'],
    'http_req_failed{name:POST /api/v1/generations}': ['rate<0.05'],
    'post_5xx': ['count<50'],
    'post_429': ['count<500'],
  },
};

export function setup() {
  const token = getToken('user', 'picturesAI');
  return { token: token };
}

export default function (data) {
  const idemKey = `thr-${__VU}-${__ITER}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const started = Date.now();

  const res = http.post(
    `${BASE}/api/v1/generations`,
    JSON.stringify({
      prompt: randomPrompt(),
      model: 'flux',
      size: '512x512',
      count: 1,
      isPublic: true,
    }),
    {
      headers: {
        Authorization: `Bearer ${data.token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': idemKey,
      },
      tags: { name: 'POST /api/v1/generations' },
    }
  );

  postDuration.add(Date.now() - started);

  if (res.status === 202) {
    accepted202.add(1);
  } else if (res.status === 429) {
    rateLimited429.add(1);
  } else if (res.status >= 500 && res.status < 600) {
    serverErrors5xx.add(1);
  } else if (res.status >= 400 && res.status < 500) {
    clientErrors4xx.add(1);
  } else {
    otherStatuses.add(1);
  }

  check(res, {
    'POST 202 or 429': (r) => r.status === 202 || r.status === 429,
  });

  const pause = 100 + Math.random() * 100;
  const elapsed = Date.now() - started;
  const sleepMs = Math.max(0, pause - elapsed);
  if (sleepMs > 0) {
    const sec = sleepMs / 1000;
    const end = Date.now() + sleepMs;
    while (Date.now() < end) {}
  }
}