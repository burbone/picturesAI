import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const KC = 'http://localhost:8090';
const REALM = 'ai-gallery';
const TOKEN_URL = `${KC}/realms/${REALM}/protocol/openid-connect/token`;
const ADMIN_TOKEN_URL = `${KC}/realms/master/protocol/openid-connect/token`;
const ADMIN_USERS_URL = `${KC}/admin/realms/${REALM}/users`;

const ADMIN_USERNAME = 'picturesAI';
const ADMIN_PASSWORD = 'picturesAI';

const createStatus201 = new Counter('kc_create_201');
const createStatus409 = new Counter('kc_create_409');
const createStatusOther = new Counter('kc_create_other');

export function getToken(username = 'user', password = 'picturesAI') {
  const res = http.post(
    TOKEN_URL,
    {
      grant_type: 'password',
      client_id: 'frontend',
      username: username,
      password: password,
    },
    { tags: { name: 'keycloak_password_grant' } }
  );
  check(res, {
    'keycloak token 200': (r) => r.status === 200,
    'keycloak token present': (r) => !!r.json('access_token'),
  });
  if (res.status !== 200) {
    console.log(`getToken(${username}) -> ${res.status} ${res.body}`);
  }
  return res.json('access_token');
}

export function getAdminToken() {
  const res = http.post(
    ADMIN_TOKEN_URL,
    {
      grant_type: 'password',
      client_id: 'admin-cli',
      username: ADMIN_USERNAME,
      password: ADMIN_PASSWORD,
    },
    { tags: { name: 'keycloak_admin_token' } }
  );
  check(res, { 'admin token 200': (r) => r.status === 200 });
  if (res.status !== 200) {
    console.log(`getAdminToken -> ${res.status} ${res.body}`);
  }
  return res.json('access_token');
}

export function getUser(adminToken, username) {
  const search = http.get(
    `${ADMIN_USERS_URL}?username=${encodeURIComponent(username)}&exact=true`,
    {
      headers: { Authorization: `Bearer ${adminToken}` },
      tags: { name: 'keycloak_search_user' },
    }
  );
  const users = search.json();
  if (!Array.isArray(users) || users.length === 0) {
    return null;
  }
  return users[0];
}

export function createUser(adminToken, username, password, email) {
  const res = http.post(
    ADMIN_USERS_URL,
    JSON.stringify({
      username: username,
      email: email,
      firstName: 'Load',
      lastName: username,
      enabled: true,
      emailVerified: true,
      requiredActions: [],
      credentials: [
        { type: 'password', value: password, temporary: false },
      ],
    }),
    {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
      },
      tags: { name: 'keycloak_create_user' },
    }
  );
  if (res.status === 201) {
    createStatus201.add(1);
  } else if (res.status === 409) {
    createStatus409.add(1);
  } else {
    createStatusOther.add(1);
    console.log(`createUser(${username}) -> ${res.status} ${res.body}`);
  }
  check(res, {
    'create user 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
  return res.status;
}

export function updateUser(adminToken, userId, username, email) {
  const res = http.put(
    `${ADMIN_USERS_URL}/${userId}`,
    JSON.stringify({
      username: username,
      email: email,
      firstName: 'Load',
      lastName: username,
      enabled: true,
      emailVerified: true,
      requiredActions: [],
    }),
    {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
      },
      tags: { name: 'keycloak_update_user' },
    }
  );
  check(res, { 'update user 204': (r) => r.status === 204 });
  if (res.status !== 204) {
    console.log(`updateUser(${username}) -> ${res.status} ${res.body}`);
  }
  return res.status;
}

export function setUserPassword(adminToken, userId, password) {
  const res = http.put(
    `${ADMIN_USERS_URL}/${userId}/reset-password`,
    JSON.stringify({ type: 'password', value: password, temporary: false }),
    {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
      },
      tags: { name: 'keycloak_reset_password' },
    }
  );
  check(res, { 'reset password 204': (r) => r.status === 204 });
  if (res.status !== 204) {
    console.log(`setUserPassword(${userId}) -> ${res.status} ${res.body}`);
  }
  return res.status;
}

export function deleteUser(adminToken, username) {
  const user = getUser(adminToken, username);
  if (!user) {
    return 0;
  }
  const res = http.del(`${ADMIN_USERS_URL}/${user.id}`, null, {
    headers: { Authorization: `Bearer ${adminToken}` },
    tags: { name: 'keycloak_delete_user' },
  });
  return res.status;
}

export function ensureUser(adminToken, username, password, email) {
  const existing = getUser(adminToken, username);
  if (existing) {
    updateUser(adminToken, existing.id, username, email);
    setUserPassword(adminToken, existing.id, password);
  } else {
    createUser(adminToken, username, password, email);
    const created = getUser(adminToken, username);
    if (created) {
      setUserPassword(adminToken, created.id, password);
    }
  }
  return getToken(username, password);
}