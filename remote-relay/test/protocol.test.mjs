import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import WebSocket from 'ws';
import { createRelayServer } from '../server.mjs';

function nextMessage(socket, binary = false) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out waiting for WebSocket message')), 2000);
    const onMessage = (data, isBinary) => {
      if (binary !== isBinary) return;
      clearTimeout(timer);
      socket.off('message', onMessage);
      resolve(data);
    };
    socket.on('message', onMessage);
  });
}

async function connect(url) {
  const socket = new WebSocket(url);
  await once(socket, 'open');
  return socket;
}

test('pairs one viewer, forwards frames and only validated control messages, then drops room on host stop', async () => {
  const { httpServer, wss } = createRelayServer();
  httpServer.listen(0, '127.0.0.1');
  await once(httpServer, 'listening');
  const { port } = httpServer.address();
  const url = `ws://127.0.0.1:${port}/ws`;
  const code = 'ABCD2EFGH3';
  let host;
  let viewer;
  try {
    host = await connect(url);
    const hostReady = nextMessage(host);
    host.send(JSON.stringify({ type: 'host', code }));
    assert.equal(JSON.parse(await hostReady).type, 'ready');

    const invalidAuth = await connect(url);
    const invalidAuthClosed = once(invalidAuth, 'close');
    invalidAuth.send('null');
    assert.equal((await invalidAuthClosed)[0], 1008);

    viewer = await connect(url);
    const viewerReady = nextMessage(viewer);
    const joined = nextMessage(host);
    viewer.send(JSON.stringify({ type: 'viewer', code }));
    assert.equal(JSON.parse(await viewerReady).type, 'ready');
    assert.equal(JSON.parse(await joined).type, 'viewer_joined');

    const frame = Buffer.from([0xff, 0xd8, 0x01, 0x02, 0xff, 0xd9]);
    const receivedFrame = nextMessage(viewer, true);
    host.send(frame);
    assert.deepEqual(await receivedFrame, frame);

    const paused = nextMessage(viewer);
    host.send(JSON.stringify({ type: 'paused' }));
    assert.equal(JSON.parse(await paused).type, 'paused');
    let pausedControls = 0;
    let pausedFrames = 0;
    const countControl = () => { pausedControls++; };
    const countFrame = (_data, binary) => { if (binary) pausedFrames++; };
    host.on('message', countControl);
    viewer.on('message', countFrame);
    viewer.send(JSON.stringify({ type: 'control', action: 'tap', x: 0.1, y: 0.1 }));
    host.send(frame);
    await new Promise((resolve) => setTimeout(resolve, 100));
    host.off('message', countControl);
    viewer.off('message', countFrame);
    assert.equal(pausedControls, 0);
    assert.equal(pausedFrames, 0);
    const resumed = nextMessage(viewer);
    host.send(JSON.stringify({ type: 'resumed' }));
    assert.equal(JSON.parse(await resumed).type, 'resumed');

    const control = nextMessage(host);
    viewer.send(JSON.stringify({ type: 'control', action: 'tap', x: 0.25, y: 0.75 }));
    assert.deepEqual(JSON.parse(await control), { type: 'control', action: 'tap', x: 0.25, y: 0.75 });

    const invalidClose = once(viewer, 'close');
    viewer.send(JSON.stringify({ type: 'control', action: 'shell', command: 'nope' }));
    const [closeCode] = await invalidClose;
    assert.equal(closeCode, 1008);

    const hostClosed = once(host, 'close');
    host.close(1000, 'test stop');
    await hostClosed;
  } finally {
    if (viewer && viewer.readyState < WebSocket.CLOSING) viewer.terminate();
    if (host && host.readyState < WebSocket.CLOSING) host.terminate();
    await new Promise((resolve) => wss.close(resolve));
    await new Promise((resolve) => httpServer.close(resolve));
  }
});
