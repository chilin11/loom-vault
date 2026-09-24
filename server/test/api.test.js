'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { after, before, test } = require('node:test');
const { createService } = require('../server');

let workDir;
let dataFile;
let service;
let baseUrl;

async function startService() {
  service = await createService({ dataFile });
  await new Promise((resolve, reject) => {
    service.server.once('error', reject);
    service.server.listen(0, '127.0.0.1', resolve);
  });
  baseUrl = `http://127.0.0.1:${service.server.address().port}`;
}

async function request(route, { method = 'GET', token, body } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${baseUrl}${route}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await response.json();
  return { status: response.status, payload };
}

before(async () => {
  workDir = await fs.mkdtemp(path.join(process.env.PI_SCRATCH_DIR || os.tmpdir(), 'vault-api-test-'));
  dataFile = path.join(workDir, 'database.json');
  await startService();
});

after(async () => {
  if (service) await service.close();
  if (workDir) await fs.rm(workDir, { recursive: true, force: true });
});

test('registration and login return account session and vault state', async () => {
  const registered = await request('/api/register', {
    method: 'POST', body: { username: 'alice', password: 'correct horse battery staple' },
  });
  assert.equal(registered.status, 201);
  assert.equal(registered.payload.revision, 0);
  assert.equal(registered.payload.vault, null);
  assert.match(registered.payload.token, /^[A-Za-z0-9_-]{43}$/);

  const loggedIn = await request('/api/login', {
    method: 'POST', body: { username: 'ALICE', password: 'correct horse battery staple' },
  });
  assert.equal(loggedIn.status, 200);
  assert.equal(loggedIn.payload.revision, 0);
  assert.equal(loggedIn.payload.vault, null);
  assert.match(loggedIn.payload.token, /^[A-Za-z0-9_-]{43}$/);

  const wrongPassword = await request('/api/login', {
    method: 'POST', body: { username: 'alice', password: 'incorrect password' },
  });
  assert.equal(wrongPassword.status, 401);
  assert.deepEqual(wrongPassword.payload, { error: 'invalid_credentials' });
});

test('vault requires a session and returns an opaque envelope', async () => {
  const unauthenticated = await request('/api/vault');
  assert.equal(unauthenticated.status, 401);

  const registration = await request('/api/register', {
    method: 'POST', body: { username: 'bob', password: 'a secure account password' },
  });
  const unauthorized = await request('/api/vault', { token: 'not-a-token' });
  assert.equal(unauthorized.status, 401);

  const saved = await request('/api/vault', {
    method: 'PUT', token: registration.payload.token,
    body: { revision: 0, vault: 'opaque-encrypted-envelope:🗝️' },
  });
  assert.equal(saved.status, 200);
  assert.deepEqual(saved.payload, { revision: 1 });
  const loaded = await request('/api/vault', { token: registration.payload.token });
  assert.deepEqual(loaded.payload, { revision: 1, vault: 'opaque-encrypted-envelope:🗝️' });
});

test('stale revision returns the required conflict body', async () => {
  const registration = await request('/api/register', {
    method: 'POST', body: { username: 'carol', password: 'a separate account password' },
  });
  const token = registration.payload.token;
  await request('/api/vault', { method: 'PUT', token, body: { revision: 0, vault: 'current ciphertext' } });

  const conflict = await request('/api/vault', {
    method: 'PUT', token, body: { revision: 0, vault: 'stale ciphertext' },
  });
  assert.equal(conflict.status, 409);
  assert.deepEqual(conflict.payload, { error: 'conflict', revision: 1, vault: 'current ciphertext' });
});

test('database survives a service restart', async () => {
  const registration = await request('/api/register', {
    method: 'POST', body: { username: 'dana', password: 'yet another secure password' },
  });
  await request('/api/vault', {
    method: 'PUT', token: registration.payload.token,
    body: { revision: 0, vault: 'persisted opaque ciphertext' },
  });

  await service.close();
  await startService();
  const login = await request('/api/login', {
    method: 'POST', body: { username: 'dana', password: 'yet another secure password' },
  });
  assert.equal(login.status, 200);
  assert.equal(login.payload.revision, 1);
  assert.equal(login.payload.vault, 'persisted opaque ciphertext');
});

test('invalid JSON, credentials, revisions, and vault values have JSON errors', async () => {
  const invalidJson = await fetch(`${baseUrl}/api/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{',
  });
  assert.equal(invalidJson.status, 400);
  assert.deepEqual(await invalidJson.json(), { error: 'invalid_json' });

  const invalidInput = await request('/api/register', {
    method: 'POST', body: { username: 'x!', password: 'short' },
  });
  assert.equal(invalidInput.status, 400);
  assert.deepEqual(invalidInput.payload, { error: 'invalid_request' });

  const duplicate = await request('/api/register', {
    method: 'POST', body: { username: 'dana', password: 'yet another secure password' },
  });
  assert.equal(duplicate.status, 409);
  assert.deepEqual(duplicate.payload, { error: 'username_taken' });

  const login = await request('/api/login', {
    method: 'POST', body: { username: 'dana', password: 'yet another secure password' },
  });
  for (const body of [
    { revision: -1, vault: 'valid envelope' },
    { revision: 1, vault: '' },
    { revision: 1, vault: { encrypted: true } },
  ]) {
    const invalidVault = await request('/api/vault', { method: 'PUT', token: login.payload.token, body });
    assert.equal(invalidVault.status, 400);
    assert.deepEqual(invalidVault.payload, { error: 'invalid_request' });
  }
});
