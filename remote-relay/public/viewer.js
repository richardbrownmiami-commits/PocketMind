const codeInput = document.querySelector('#code');
const connectButton = document.querySelector('#connect');
const statusNode = document.querySelector('#status');
const viewerPanel = document.querySelector('#viewer');
const pairingPanel = document.querySelector('#pairing');
const image = document.querySelector('#screen');
const placeholder = document.querySelector('#placeholder');
const textInput = document.querySelector('#text');
let socket = null;
let previousUrl = null;
let pointerStart = null;
let lastControlAt = 0;
let canControl = false;

function setStatus(message) { statusNode.textContent = message; }
function cleanupImage() {
  canControl = false;
  pointerStart = null;
  image.hidden = true;
  placeholder.hidden = false;
  if (previousUrl) URL.revokeObjectURL(previousUrl);
  previousUrl = null;
  image.removeAttribute('src');
}
function disconnect(message = 'Disconnected') {
  if (socket) {
    const old = socket;
    socket = null;
    old.close(1000, 'Viewer disconnected');
  }
  cleanupImage();
  viewerPanel.hidden = true;
  pairingPanel.hidden = false;
  connectButton.disabled = false;
  setStatus(message);
}
function sendControl(control) {
  if (!canControl || !socket || socket.readyState !== WebSocket.OPEN) return;
  const now = Date.now();
  if (now - lastControlAt < 70) return;
  lastControlAt = now;
  socket.send(JSON.stringify({ type: 'control', ...control }));
}
function point(event) {
  const box = image.getBoundingClientRect();
  if (!box.width || !box.height) return null;
  return {
    x: Math.max(0, Math.min(1, (event.clientX - box.left) / box.width)),
    y: Math.max(0, Math.min(1, (event.clientY - box.top) / box.height))
  };
}

connectButton.addEventListener('click', () => {
  const code = codeInput.value.trim().replace(/[\s-]/g, '').toUpperCase();
  if (!/^[A-HJ-NP-Z2-9]{10}$/.test(code)) {
    setStatus('That code does not look valid.');
    return;
  }
  connectButton.disabled = true;
  const wsScheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  socket = new WebSocket(`${wsScheme}//${location.host}/ws`);
  socket.binaryType = 'blob';
  socket.addEventListener('open', () => socket?.send(JSON.stringify({ type: 'viewer', code })));
  socket.addEventListener('message', (event) => {
    if (event.data instanceof Blob) {
      if (previousUrl) URL.revokeObjectURL(previousUrl);
      previousUrl = URL.createObjectURL(event.data);
      image.src = previousUrl;
      image.hidden = false;
      placeholder.hidden = true;
      canControl = true;
      setStatus('Connected — receiving PocketMind app window');
      return;
    }
    let message;
    try { message = JSON.parse(event.data); } catch { disconnect('Relay sent an invalid message'); return; }
    if (message.type === 'ready') {
      pairingPanel.hidden = true;
      viewerPanel.hidden = false;
      setStatus(message.paused ? 'Paused — reopen PocketMind on the phone' : 'Connected — waiting for the phone screen');
      codeInput.value = '';
    } else if (message.type === 'paused') {
      cleanupImage();
      setStatus('Paused — reopen PocketMind on the phone');
    } else if (message.type === 'resumed') {
      cleanupImage();
      setStatus('Connected — waiting for a fresh PocketMind frame');
    } else if (message.type === 'viewer_joined') {
      setStatus('Connected');
    } else if (message.type === 'error') {
      disconnect(message.message || 'Pairing failed');
    }
  });
  socket.addEventListener('close', (event) => {
    if (socket) disconnect(event.reason || 'Session ended');
  });
  socket.addEventListener('error', () => setStatus('Connection failed. Check the relay and try again.'));
  setStatus('Connecting…');
});

image.addEventListener('pointerdown', (event) => {
  const p = point(event);
  if (!p) return;
  pointerStart = { ...p, x0: event.clientX, y0: event.clientY };
  image.setPointerCapture(event.pointerId);
});
image.addEventListener('pointerup', (event) => {
  if (!pointerStart) return;
  const end = point(event);
  const start = pointerStart;
  pointerStart = null;
  if (!end) return;
  const distance = Math.hypot(event.clientX - start.x0, event.clientY - start.y0);
  if (distance < 12) sendControl({ action: 'tap', x: start.x, y: start.y });
  else sendControl({ action: 'swipe', x: start.x, y: start.y, x2: end.x, y2: end.y });
});
image.addEventListener('pointercancel', () => { pointerStart = null; });
document.querySelector('#sendText').addEventListener('click', () => {
  if (!textInput.value) return;
  sendControl({ action: 'text', text: textInput.value.slice(0, 1000) });
  textInput.value = '';
});
document.querySelector('#disconnect').addEventListener('click', () => disconnect());
window.addEventListener('pagehide', () => {
  if (socket) socket.close(1000, 'Viewer closed');
  cleanupImage();
});
