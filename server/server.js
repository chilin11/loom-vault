'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const { TextDecoder } = require('node:util');

const MAX_BODY_BYTES = 13 * 1024 * 1024;
const MAX_VAULT_BYTES = 2 * 1024 * 1024;
const PASSWORD_MIN_BYTES = 8;
const PASSWORD_MAX_BYTES = 1024;
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const AUTH_LIMIT = 10;
const AUTH_WINDOW_MS = 15 * 60 * 1000;
const API_LIMIT = 120;
const API_WINDOW_MS = 60 * 1000;
const SCRYPT_OPTIONS = { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
const HASH_BYTES = 64;

class HttpError extends Error {
  constructor(status, error, headers = {}, details = {}) {
    super(error);
    this.status = status;
    this.error = error;
    this.headers = headers;
    this.details = details;
  }
}

function scrypt(password, salt) {
  return new Promise((resolve, reject) => {
    crypto.scrypt(password, salt, HASH_BYTES, SCRYPT_OPTIONS, (error, key) => {
      if (error) reject(error);
      else resolve(key);
    });
  });
}

function validateDatabase(database) {
  if (!database || database.version !== 1 || !database.users ||
      typeof database.users !== 'object' || Array.isArray(database.users)) {
    throw new Error('Invalid database format');
  }

  for (const [username, user] of Object.entries(database.users)) {
    if (!/^[a-z0-9][a-z0-9._-]{2,63}$/.test(username) ||
        !user || typeof user !== 'object' ||
        !Number.isSafeInteger(user.revision) || user.revision < 0 ||
        !(user.vault === null || typeof user.vault === 'string') ||
        (typeof user.vault === 'string' && Buffer.byteLength(user.vault, 'utf8') > MAX_VAULT_BYTES) ||
        typeof user.salt !== 'string' || typeof user.hash !== 'string') {
      throw new Error('Invalid database user record');
    }
    const salt = Buffer.from(user.salt, 'base64url');
    const hash = Buffer.from(user.hash, 'base64url');
    if (salt.length !== 16 || hash.length !== HASH_BYTES) {
      throw new Error('Invalid database password hash');
    }
  }
  return database;
}

async function persistDatabase(filePath, database) {
  const directory = path.dirname(filePath);
  const temporaryPath = `${filePath}.${process.pid}.${crypto.randomBytes(8).toString('hex')}.tmp`;
  let handle;
  try {
    handle = await fs.open(temporaryPath, 'wx', 0o600);
    await handle.writeFile(JSON.stringify(database), 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await fs.rename(temporaryPath, filePath);

    // Persist the rename where directory fsync is supported.
    let directoryHandle;
    try {
      directoryHandle = await fs.open(directory, 'r');
      await directoryHandle.sync();
    } catch (error) {
      if (!['EINVAL', 'ENOTSUP', 'EISDIR', 'EBADF'].includes(error.code)) throw error;
    } finally {
      if (directoryHandle) await directoryHandle.close();
    }
  } finally {
    if (handle) await handle.close().catch(() => {});
    await fs.unlink(temporaryPath).catch(() => {});
  }
}

async function loadDatabase(filePath) {
  await fs.mkdir(path.dirname(filePath), { recursive: true, mode: 0o700 });
  try {
    const text = await fs.readFile(filePath, 'utf8');
    const database = validateDatabase(JSON.parse(text));
    await fs.chmod(filePath, 0o600);
    return database;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }

  const database = { version: 1, users: {} };
  await persistDatabase(filePath, database);
  return database;
}

function sendJson(response, status, payload, headers = {}) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  response.end(body);
}

async function readJson(request) {
  const contentType = request.headers['content-type'] || '';
  if (!/^application\/json(?:\s*;|\s*$)/i.test(contentType)) {
    request.resume();
    throw new HttpError(415, 'unsupported_media_type');
  }
  const contentEncoding = request.headers['content-encoding'];
  if (contentEncoding && contentEncoding !== 'identity') {
    request.resume();
    throw new HttpError(415, 'unsupported_content_encoding');
  }
  const contentLength = Number(request.headers['content-length']);
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    request.resume();
    throw new HttpError(413, 'body_too_large');
  }

  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      // Drain the bounded-by-transport request without retaining more body bytes.
      chunks.length = 0;
      for await (const _ignored of request) { /* drain */ }
      throw new HttpError(413, 'body_too_large');
    }
    chunks.push(chunk);
  }
  if (size === 0) throw new HttpError(400, 'invalid_json');

  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, 'invalid_json');
  }
}

function requireObject(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new HttpError(400, 'invalid_request');
  }
  return value;
}

function normalizeUsername(value) {
  if (typeof value !== 'string') throw new HttpError(400, 'invalid_request');
  const username = value.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/.test(username)) {
    throw new HttpError(400, 'invalid_request');
  }
  return username.toLowerCase();
}

function validatePassword(value) {
  if (typeof value !== 'string') throw new HttpError(400, 'invalid_request');
  const bytes = Buffer.byteLength(value, 'utf8');
  if (bytes < PASSWORD_MIN_BYTES || bytes > PASSWORD_MAX_BYTES) {
    throw new HttpError(400, 'invalid_request');
  }
  return value;
}

function validateVault(value) {
  if (typeof value !== 'string' || value.length === 0 ||
      Buffer.byteLength(value, 'utf8') > MAX_VAULT_BYTES) {
    throw new HttpError(400, 'invalid_request');
  }
  return value;
}

function clientAddress(request) {
  // Do not trust forwarded headers; the app may sit behind a configured proxy.
  return request.socket.remoteAddress || 'unknown';
}

async function createService(options = {}) {
  const dataFile = path.resolve(options.dataFile || process.env.DATA_FILE || path.join(__dirname, 'data.json'));
  let database = await loadDatabase(dataFile);
  const sessionTtlMs = options.sessionTtlMs || SESSION_TTL_MS;
  const sessions = new Map();
  const rateBuckets = new Map();
  let mutationQueue = Promise.resolve();

  const dummySalt = Buffer.alloc(16, 0x5a);
  const dummyHash = await scrypt('not-a-real-account-password', dummySalt);

  function serializeMutation(operation) {
    const pending = mutationQueue.then(operation);
    mutationQueue = pending.catch(() => {});
    return pending;
  }

  function isRateLimited(request, route) {
    const isAuthRoute = route === '/api/register' || route === '/api/login';
    const limit = isAuthRoute ? AUTH_LIMIT : API_LIMIT;
    const windowMs = isAuthRoute ? AUTH_WINDOW_MS : API_WINDOW_MS;
    const key = `${isAuthRoute ? 'auth' : 'api'}:${clientAddress(request)}`;
    const now = Date.now();
    let bucket = rateBuckets.get(key);
    if (!bucket || bucket.resetAt <= now) {
      if (rateBuckets.size >= 10000) {
        for (const [oldKey, oldBucket] of rateBuckets) {
          if (oldBucket.resetAt <= now || rateBuckets.size >= 10000) rateBuckets.delete(oldKey);
          if (rateBuckets.size < 9000) break;
        }
      }
      bucket = { count: 0, resetAt: now + windowMs };
    }
    bucket.count += 1;
    rateBuckets.set(key, bucket);
    if (bucket.count > limit) {
      const retrySeconds = Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
      return { 'Retry-After': String(retrySeconds) };
    }
    return null;
  }

  function createSession(username) {
    const now = Date.now();
    for (const [token, session] of sessions) {
      if (session.expiresAt <= now) sessions.delete(token);
    }
    while (sessions.size >= 100000) sessions.delete(sessions.keys().next().value);
    const token = crypto.randomBytes(32).toString('base64url');
    sessions.set(token, { username, expiresAt: now + sessionTtlMs });
    return token;
  }

  function getAuthenticatedUser(request) {
    const authorization = request.headers.authorization || '';
    const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authorization);
    if (!match) throw new HttpError(401, 'unauthorized');
    const session = sessions.get(match[1]);
    if (!session || session.expiresAt <= Date.now()) {
      sessions.delete(match[1]);
      throw new HttpError(401, 'unauthorized');
    }
    const user = database.users[session.username];
    if (!user) throw new HttpError(401, 'unauthorized');
    return { username: session.username, user };
  }

  async function handle(request, response) {
    const url = new URL(request.url || '/', 'http://localhost');
    const route = url.pathname;
    if (!route.startsWith('/api/')) throw new HttpError(404, 'not_found');
    const limitHeaders = isRateLimited(request, route);
    if (limitHeaders) throw new HttpError(429, 'rate_limited', limitHeaders);

    if (route === '/api/register' && request.method === 'POST') {
      const body = requireObject(await readJson(request));
      const username = normalizeUsername(body.username);
      const password = validatePassword(body.password);
      const result = await serializeMutation(async () => {
        if (database.users[username]) throw new HttpError(409, 'username_taken');
        const salt = crypto.randomBytes(16);
        const hash = await scrypt(password, salt);
        const user = { salt: salt.toString('base64url'), hash: hash.toString('base64url'), revision: 0, vault: null };
        const next = { version: 1, users: { ...database.users, [username]: user } };
        await persistDatabase(dataFile, next);
        database = next;
        return user;
      });
      sendJson(response, 201, { token: createSession(username), revision: result.revision, vault: result.vault });
      return;
    }

    if (route === '/api/login' && request.method === 'POST') {
      const body = requireObject(await readJson(request));
      const username = normalizeUsername(body.username);
      const password = validatePassword(body.password);
      const user = database.users[username];
      const salt = user ? Buffer.from(user.salt, 'base64url') : dummySalt;
      const expected = user ? Buffer.from(user.hash, 'base64url') : dummyHash;
      const actual = await scrypt(password, salt);
      const passwordMatches = crypto.timingSafeEqual(actual, expected);
      if (!user || !passwordMatches) throw new HttpError(401, 'invalid_credentials');
      sendJson(response, 200, {
        token: createSession(username),
        revision: user.revision,
        vault: user.vault,
      });
      return;
    }

    if (route === '/api/vault' && request.method === 'GET') {
      const { user } = getAuthenticatedUser(request);
      sendJson(response, 200, { revision: user.revision, vault: user.vault });
      return;
    }

    if (route === '/api/vault' && request.method === 'PUT') {
      const { username } = getAuthenticatedUser(request);
      const body = requireObject(await readJson(request));
      if (!Number.isSafeInteger(body.revision) || body.revision < 0) {
        throw new HttpError(400, 'invalid_request');
      }
      const vault = validateVault(body.vault);
      const updated = await serializeMutation(async () => {
        const current = database.users[username];
        if (current.revision !== body.revision) {
          throw new HttpError(409, 'conflict', {}, { revision: current.revision, vault: current.vault });
        }
        const nextUser = { ...current, revision: current.revision + 1, vault };
        const next = { version: 1, users: { ...database.users, [username]: nextUser } };
        await persistDatabase(dataFile, next);
        database = next;
        return nextUser;
      });
      sendJson(response, 200, { revision: updated.revision });
      return;
    }

    if (route === '/api/register' || route === '/api/login' || route === '/api/vault') {
      throw new HttpError(405, 'method_not_allowed', { Allow: route === '/api/vault' ? 'GET, PUT' : 'POST' });
    }
    throw new HttpError(404, 'not_found');
  }

  const server = http.createServer({ maxHeaderSize: 16 * 1024 }, (request, response) => {
    response.on('error', () => {});
    handle(request, response).catch((error) => {
      if (response.headersSent) {
        response.destroy();
        return;
      }
      if (error instanceof HttpError) {
        sendJson(response, error.status, { error: error.error, ...error.details }, error.headers);
        return;
      }
      sendJson(response, 500, { error: 'internal_error' });
    });
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5_000;

  return {
    server,
    async close() {
      sessions.clear();
      if (server.listening) {
        await new Promise((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve());
        });
      }
    },
  };
}

async function main() {
  const port = Number(process.env.PORT || 3000);
  const host = process.env.HOST || '127.0.0.1';
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('PORT must be an integer from 1 to 65535');
  }
  const service = await createService();
  service.server.on('error', (error) => {
    console.error(`Server error: ${error.message}`);
    process.exitCode = 1;
  });
  service.server.listen(port, host, () => {
    console.log(`Password notebook API listening on ${host}:${port}`);
  });
  const shutdown = () => service.close().catch((error) => {
    console.error(`Shutdown error: ${error.message}`);
    process.exitCode = 1;
  });
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(`Startup failed: ${error.message}`);
    process.exitCode = 1;
  });
}

module.exports = { createService };
