# PocketMind remote-test relay

This relay is an optional companion for PocketMind's user-started remote-test mode. Normal builds have no relay address. For continued use, deploy it on a stable host. A one-off APK may prefill an approved temporary endpoint, usable only while its workspace is active; that is not a permanent relay.

To prefill one regular APK without changing source defaults, pass `-Ppocketmind.remoteRelayUrl=https://your-approved-relay.example` to Gradle. The property only fills the editable connection dialog; it never starts a session automatically. Do not commit temporary addresses to Gradle properties or source. Without this property, `DEFAULT_REMOTE_RELAY_URL` is empty. No extra APK variant or tester module is created.

## Run

Requirements: Node.js 18 or newer and a stable HTTPS domain. Install dependencies and run the protocol test:

```sh
npm ci
npm test
HOST=127.0.0.1 PORT=8080 npm start
```

Keep the Node process reachable only from a trusted TLS reverse proxy. Terminate HTTPS/WSS at the proxy, forward `/` and `/viewer.js` to the app, and upgrade `/ws` to WebSocket. Do not expose the plain Node HTTP listener directly to the public internet. The app accepts only an HTTPS root URL and always uses WSS for its connection. The viewer opens at that HTTPS root.

Example Nginx location for the WebSocket path (also configure `/` and `/viewer.js` as ordinary proxied HTTP):

```nginx
location /ws {
    proxy_pass http://127.0.0.1:8080/ws;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 70m;
}
```

## How sessions work

1. In PocketMind, select **Start remote test**, enter your relay's HTTPS root address, and confirm the disclosure.
2. PocketMind creates a fresh cryptographically random 10-character code (50 bits, excluding ambiguous I/O/0/1) and opens an outbound WSS connection. Open the relay's root page on the viewer and enter the code; optional grouping hyphens are accepted. Share the code only with the tester you intend to authorize.
3. The browser receives reduced-resolution JPEG frames (up to 480 pixels wide, about 3 frames per second, at most 256 KiB per frame) and can send normalized taps/swipes or text to the focused PocketMind input.
4. Switching apps pauses frames/control and blanks the viewer, allowing the owner to share the code and return. The active notification remains with Stop. The session ends on **Stop**, screen off/lock, app task removal, service/network failure, or the relay's 60-minute maximum. The code is held only in memory and is not saved by the app; copying it to the clipboard requires the owner to tap **Copy code**.

## Privacy and limits

- PocketMind captures only its own application `Window` with Android `PixelCopy`; it does not request system screen recording. Capture is skipped when the app window is not focused, and failed/oversized frames are discarded.
- Remote input is dispatched locally to PocketMind's own view tree and a focused PocketMind text field. There is no Accessibility service, shell, file transfer, or remote injection into another app.
- Chat requests submitted during a live user-started session use PocketMind's normal router and runtime, including alarms, timers, flashlight, volume, brightness, installed-app queries/launches, and settings. These actions may affect the phone; the app displays its usual verified result, Android hand-off, or failure in PocketMind chat. Stop prevents not-yet-run actions from requests submitted in that session; normal off-session requests keep their existing behavior.
- If an action opens another app, Android backgrounds PocketMind: the app's own window capture stops, the relay blanks the viewer and blocks remote controls while paused. Returning to PocketMind resumes its window view/control. The relay does not capture or control the other app.
- The phone shows a persistent notification and a clear in-app active banner with a Stop button. Normal chat and other features do not connect to the relay.
- The relay keeps room credentials, one active host and one viewer in memory only. It does not write frames, pairing codes, or controls to disk or log their content. It enforces one viewer, an eight-attempt/minute pairing limit per source address, a twelve-control/second limit, bounded messages, a 60-minute session limit, and closes a room when the host disconnects.
- TLS protects the connection in transit, but the relay process handles the unencrypted image/control payloads. The relay operator can inspect live PocketMind screens. Run it only on a server you control and trust; this is not end-to-end encryption. Pairing code holders can view/control PocketMind until the session ends.
- Session/counter state is in memory. Restarting the relay ends active sessions and resets the rate limits. Use a production reverse proxy with TLS, access monitoring, host firewalling, and ordinary OS process supervision.

This feature remains opt-in and does not change PocketMind's Android 11/API 30 or ARMv7-only target.
