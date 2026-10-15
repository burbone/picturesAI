import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { getAdminToken, ensureUser, getToken } from './auth.js';
import { randomPrompt } from './prompts.js';

const BASE = 'http://localhost:8080';
const WS_BASE = 'ws://localhost:8083';
const USERS = Number(__ENV.USERS || 10);
const ITERATIONS_PER_VU = Number(__ENV.ITERS || 5);
const PASSWORD = 'picturesAI';
const WS_TIMEOUT_MS = Number(__ENV.WS_TIMEOUT || 600000);

const wsCompletedDuration = new Trend('ws_completed_duration', true);
const wsTimeouts = new Counter('ws_timeouts');
const wsFailures = new Counter('ws_failures');
const jobCompleted = new Counter('job_terminal_completed');
const jobFailed = new Counter('job_terminal_failed');
const jobStuck = new Counter('job_stuck_non_terminal');

export const options = {
  scenarios: {
    realistic: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: ITERATIONS_PER_VU,
      maxDuration: '60m',
    },
  },
  thresholds: {
    'http_req_duration{name:POST /api/v1/generations}': ['p(95)<2000'],
    'checks': ['rate>0.95'],
    'job_stuck_non_terminal': ['count==0'],
  },
};

export function setup() {
  const adminToken = getAdminToken();
  for (let i = 1; i <= USERS; i++) {
    const username = `loaduser${i}`;
    const email = `loaduser${i}@load.local`;
    ensureUser(adminToken, username, PASSWORD, email);
  }
  return {};
}

function extractStatus(payload) {
  if (!payload) return '';
  if (typeof payload.status === 'string') return payload.status;
  if (typeof payload.type === 'string') {
    if (payload.type.indexOf('COMPLETED') !== -1) return 'COMPLETED';
    if (payload.type.indexOf('FAILED') !== -1) return 'FAILED';
    if (payload.type.indexOf('CANCELLED') !== -1) return 'CANCELLED';
    if (payload.type.indexOf('ERROR') !== -1) return 'FAILED';
  }
  if (payload.event && typeof payload.event === 'string') {
    if (payload.event.indexOf('COMPLETED') !== -1) return 'COMPLETED';
    if (payload.event.indexOf('FAILED') !== -1) return 'FAILED';
    if (payload.event.indexOf('CANCELLED') !== -1) return 'CANCELLED';
  }
  return '';
}

function extractImageUrl(payload) {
  if (!payload) return '';
  if (typeof payload.imageUrl === 'string' && payload.imageUrl.length > 0) return payload.imageUrl;
  if (payload.data && typeof payload.data.imageUrl === 'string') return payload.data.imageUrl;
  if (payload.payload && typeof payload.payload.imageUrl === 'string') return payload.payload.imageUrl;
  if (Array.isArray(payload.images) && payload.images.length > 0) {
    const first = payload.images[0];
    if (typeof first === 'string') return first;
    if (first && typeof first.url === 'string') return first.url;
    if (first && typeof first.imageUrl === 'string') return first.imageUrl;
  }
  return '';
}

export default function () {
  const username = `loaduser${__VU}`;
  const token = getToken(username, PASSWORD);
  const prompt = randomPrompt();
  const idemKey = `${__VU}-${__ITER}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

  const createRes = http.post(
    `${BASE}/api/v1/generations`,
    JSON.stringify({
      prompt: prompt,
      model: 'flux',
      size: '512x512',
      count: 1,
      isPublic: true,
    }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': idemKey,
      },
      tags: { name: 'POST /api/v1/generations' },
    }
  );

  const postOk = check(createRes, {
    'POST returned 202': (r) => r.status === 202,
    'POST has jobId': (r) => {
      try {
        return typeof r.json('jobId') === 'string' && r.json('jobId').length > 0;
      } catch (e) {
        return false;
      }
    },
  });
  if (!postOk) {
    console.log(`POST_FAIL vu=${__VU} iter=${__ITER} status=${createRes.status} body=${createRes.body}`);
    return;
  }

  const jobId = createRes.json('jobId');
  const wsUrl = `${WS_BASE}/ws/generations/${jobId}?token=${encodeURIComponent(token)}`;

  let gotCompleted = false;
  let gotFailed = false;
  let gotCancelled = false;
  let imageUrl = '';
  let closed = false;
  let socketError = false;
  const started = Date.now();

  const wsRes = ws.connect(wsUrl, {}, function (socket) {
    socket.on('message', function (msg) {
      let payload;
      try {
        payload = JSON.parse(msg);
      } catch (e) {
        return;
      }
      const status = extractStatus(payload);
      if (status === 'COMPLETED') {
        gotCompleted = true;
        imageUrl = extractImageUrl(payload);
        wsCompletedDuration.add(Date.now() - started);
        if (!closed) {
          closed = true;
          socket.close();
        }
      } else if (status === 'FAILED') {
        gotFailed = true;
        wsFailures.add(1);
        if (!closed) {
          closed = true;
          socket.close();
        }
      } else if (status === 'CANCELLED') {
        gotCancelled = true;
        if (!closed) {
          closed = true;
          socket.close();
        }
      }
    });

    socket.on('error', function () {
      socketError = true;
      if (!closed) {
        closed = true;
        socket.close();
      }
    });

    socket.on('close', function () {
      if (!closed) {
        closed = true;
      }
    });

    socket.setTimeout(function () {
      if (!gotCompleted && !gotFailed && !gotCancelled) {
        wsTimeouts.add(1);
      }
      if (!closed) {
        closed = true;
        socket.close();
      }
    }, WS_TIMEOUT_MS);
  });

  check(wsRes, {
    'WS handshake 101': (r) => r && r.status === 101,
  });

  if (gotCompleted) {
    jobCompleted.add(1);
  } else if (gotFailed) {
    jobFailed.add(1);
  } else {
    jobStuck.add(1);
  }

  check(
    {
      gotCompleted: gotCompleted,
      gotFailed: gotFailed,
      gotCancelled: gotCancelled,
      imageUrl: imageUrl,
      socketError: socketError,
    },
    {
      'WS got terminal event': (d) => d.gotCompleted || d.gotFailed || d.gotCancelled,
      'imageUrl not empty': (d) => d.gotCompleted === false || (typeof d.imageUrl === 'string' && d.imageUrl.length > 0),
      'WS no error': (d) => d.socketError === false,
    }
  );

  if (gotCompleted && imageUrl) {
    const imgRes = http.get(imageUrl, {
      tags: { name: 'GET presigned image' },
      redirects: 5,
    });
    check(imgRes, {
      'presigned image 200': (r) => r.status === 200,
      'presigned image non-empty': (r) => r.body && r.body.length > 0,
    });
  }

  sleep(1);
}

export function teardown() {
}