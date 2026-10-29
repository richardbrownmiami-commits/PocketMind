import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { WebSocketServer, WebSocket } from 'ws';

const HERE = dirname(fileURLToPath(import.meta.url));
const MAX_FRAME_BYTES = 256 * 1024;
const MAX_SESSIONS = 25;
const MAX_SESSION_MS = 60 * 60 * 1000;
const PAIR_WINDOW_MS = 60 * 1000;
const MAX_PAIR_ATTEMPTS = 8;
const MAX_CONTROLS_PER_SECOND = 12;
const rooms = new Map();
const pairAttempts = new Map();

function secureCodeMatch(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  return left.length === right.length && left.length > 0 && timingSafeEqual(left, right);
}

function validCode(code) {
  return typeof code === 'string' && /^[A-HJ-NP-Z2-9]{10}$/.test(code);
}

function allowedOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true; // Native app WebSocket clients do not send Origin.
  try {
    const parsed = new URL(origin);
    return (parsed.protocol === 'https:' || parsed.protocol === 'http:') && parsed.host === req.headers.host;
  } catch {
    return false;
  }
}

function trustedTransport(req) {
  const address = req.socket.remoteAddress ?? "";
  const loopback = address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1";
  const forwarded = String(req.headers["x-forwarded-proto"] ?? "").split(",")[0].trim().toLowerCase();
  if (loopback) return !forwarded || forwarded === "https";
  // Optional pin for a managed HTTPS port-forwarder; never trust a generic forwarded header.
  return Boolean(process.env.TRUSTED_PROXY_HOST) &&
    req.headers.host === process.env.TRUSTED_PROXY_HOST && forwarded === "https";
}

function consumePairAttempt(address) {
  const now = Date.now();
  const entries = (pairAttempts.get(address) ?? []).filter((at) => now - at < PAIR_WINDOW_MS);
  if (entries.length >= MAX_PAIR_ATTEMPTS) {
    pairAttempts.set(address, entries);
    return false;
  }
  entries.push(now);
  pairAttempts.set(address, entries);
  return true;
}

function safeSend(socket, data, binary = false) {
  if (socket?.readyState === WebSocket.OPEN && socket.bufferedAmount <= MAX_FRAME_BYTES * 2) {
    socket.send(data, { binary });
    return true;
  }
  return false;
}

function validCoordinate(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1;
}

function validControl(message) {
  if (!message || message.type !== 'control') return false;
  if (message.action === 'tap') return validCoordinate(message.x) && validCoordinate(message.y);
  if (message.action === 'swipe') {
    return validCoordinate(message.x) && validCoordinate(message.y) &&
      validCoordinate(message.x2) && validCoordinate(message.y2);
  }
  if (message.action === 'text') return typeof message.text === 'string' && message.text.length <= 1000;
  return false;
}

function dropRoom(room, reason = 'Session ended') {
  if (!room || rooms.get(room.code) !== room) return;
  rooms.delete(room.code);
  clearTimeout(room.expiry);
  if (room.host?.readyState === WebSocket.OPEN) room.host.close(1000, reason);
  if (room.viewer?.readyState === WebSocket.OPEN) room.viewer.close(1000, reason);
  room.host = null;
  room.viewer = null;
}

export function createRelayServer() {
  const httpServer = createServer(async (req, res) => {
    if (!trustedTransport(req)) {
      res.writeHead(403, { "Cache-Control": "no-store" });
      res.end("Use the trusted HTTPS reverse proxy");
      return;
    }
    let url;
    try { url = new URL(req.url ?? '/', 'http://localhost'); }
    catch { res.writeHead(400); res.end('Invalid URL'); return; }
    const headers = {
      'Cache-Control': 'no-store, max-age=0',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; connect-src 'self' wss: ws:; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
      'Permissions-Policy': 'camera=(), microphone=(), geolocation=()'
    };
    if (url.pathname === '/healthz' && req.method === 'GET') {
      res.writeHead(200, { ...headers, 'Content-Type': 'application/json; charset=utf-8' });
      res.end('{"ok":true}');
      return;
    }
    if (req.method !== 'GET' || !['/', '/viewer.js'].includes(url.pathname)) {
      res.writeHead(404, headers);
      res.end('Not found');
      return;
    }
    try {
      const file = url.pathname === '/' ? 'index.html' : 'viewer.js';
      const type = file.endsWith('.js') ? 'text/javascript; charset=utf-8' : 'text/html; charset=utf-8';
      const body = await readFile(join(HERE, 'public', file));
      res.writeHead(200, { ...headers, 'Content-Type': type });
      res.end(body);
    } catch {
      res.writeHead(500, headers);
      res.end('Relay unavailable');
    }
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME_BYTES, perMessageDeflate: false });
  httpServer.on('upgrade', (req, socket, head) => {
    let path;
    try { path = new URL(req.url ?? '/', 'http://localhost').pathname; }
    catch { socket.destroy(); return; }
    if (path !== '/ws' || !trustedTransport(req) || !allowedOrigin(req)) {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  wss.on('connection', (socket, req) => {
    const address = req.socket.remoteAddress ?? 'unknown';
    const peer = { role: null, room: null, controlWindowStartedAt: Date.now(), controlsInWindow: 0 };
    const authTimer = setTimeout(() => socket.close(1008, 'Pairing timed out'), 10_000);
    socket.on('message', (payload, isBinary) => {
      if (payload.length > MAX_FRAME_BYTES) {
        socket.close(1009, 'Message too large');
        return;
      }
      if (peer.role === null) {
        if (isBinary || !consumePairAttempt(address)) {
          socket.close(1008, 'Pairing rate limit exceeded');
          return;
        }
        let auth;
        try { auth = JSON.parse(payload.toString('utf8')); } catch { socket.close(1008, 'Invalid pairing'); return; }
        if (!auth || typeof auth !== 'object' || !validCode(auth.code) || !['host', 'viewer'].includes(auth.type)) {
          socket.close(1008, 'Invalid pairing');
          return;
        }
        if (auth.type === 'host') {
          if (rooms.size >= MAX_SESSIONS || rooms.has(auth.code)) {
            socket.close(1008, 'Could not create session');
            return;
          }
          const room = { code: auth.code, host: socket, viewer: null, expiry: null, paused: auth.paused === true };
          room.expiry = setTimeout(() => dropRoom(room, 'Session expired'), MAX_SESSION_MS);
          rooms.set(auth.code, room);
          peer.role = 'host';
          peer.room = room;
          clearTimeout(authTimer);
          safeSend(socket, JSON.stringify({ type: 'ready', expiresInMs: MAX_SESSION_MS }));
          return;
        }
        const room = rooms.get(auth.code);
        if (!room || room.viewer || room.host?.readyState !== WebSocket.OPEN || !secureCodeMatch(auth.code, room.code)) {
          socket.close(1008, 'Pairing code is invalid or already in use');
          return;
        }
        peer.role = 'viewer';
        peer.room = room;
        room.viewer = socket;
        clearTimeout(authTimer);
        safeSend(socket, JSON.stringify({ type: 'ready', expiresInMs: MAX_SESSION_MS, paused: room.paused }));
        safeSend(room.host, JSON.stringify({ type: 'viewer_joined' }));
        return;
      }

      const room = peer.room;
      if (!room || rooms.get(room.code) !== room) {
        socket.close(1008, 'Session ended');
        return;
      }
      if (peer.role === 'host') {
        if (!isBinary) {
          let status;
          try { status = JSON.parse(payload.toString('utf8')); } catch { socket.close(1008, 'Invalid host status'); return; }
          if (!['paused', 'resumed'].includes(status?.type)) { socket.close(1008, 'Invalid host status'); return; }
          room.paused = status.type === 'paused';
          safeSend(room.viewer, JSON.stringify({ type: status.type }));
          return;
        }
        if (room.paused) return;
        if (room.viewer && !safeSend(room.viewer, payload, true)) {
          room.viewer.close(1013, 'Viewer is too slow');
        }
        return;
      }

      if (isBinary) {
        socket.close(1008, 'Viewer may send controls only');
        return;
      }
      const now = Date.now();
      if (now - peer.controlWindowStartedAt >= 1000) {
        peer.controlWindowStartedAt = now;
        peer.controlsInWindow = 0;
      }
      if (++peer.controlsInWindow > MAX_CONTROLS_PER_SECOND) {
        socket.close(1008, 'Control rate exceeded');
        return;
      }
      let control;
      try { control = JSON.parse(payload.toString('utf8')); } catch { socket.close(1008, 'Invalid control'); return; }
      if (!validControl(control)) {
        socket.close(1008, 'Invalid control');
        return;
      }
      if (room.paused) return;
      if (!safeSend(room.host, JSON.stringify(control))) socket.close(1013, 'Host is unavailable');
    });

    socket.on('close', () => {
      clearTimeout(authTimer);
      const room = peer.room;
      if (!room || rooms.get(room.code) !== room) return;
      if (peer.role === 'host') {
        dropRoom(room, 'PocketMind session stopped');
      } else if (peer.role === 'viewer' && room.viewer === socket) {
        room.viewer = null;
        safeSend(room.host, JSON.stringify({ type: 'viewer_left' }));
      }
    });
    socket.on('error', () => {});
  });

  const cleanup = setInterval(() => {
    const now = Date.now();
    for (const [address, attempts] of pairAttempts) {
      const remaining = attempts.filter((at) => now - at < PAIR_WINDOW_MS);
      if (remaining.length) pairAttempts.set(address, remaining);
      else pairAttempts.delete(address);
    }
  }, PAIR_WINDOW_MS);
  cleanup.unref();
  return { httpServer, wss };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const { httpServer } = createRelayServer();
  const host = process.env.HOST ?? '127.0.0.1';
  const port = Number(process.env.PORT ?? 8080);
  httpServer.listen(port, host);
}
