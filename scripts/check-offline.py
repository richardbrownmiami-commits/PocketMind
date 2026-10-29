#!/usr/bin/env python3
"""Keep network access limited to approved online tools and user-started remote tests."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path("app/src/main")
permission = '<uses-permission android:name="android.permission.INTERNET" />'
manifest = (root / "AndroidManifest.xml").read_text(encoding="utf-8")
if manifest.count(permission) != 1:
    raise SystemExit("Network contract violated: INTERNET must be declared exactly once")
if 'android:usesCleartextTraffic="false"' not in manifest:
    raise SystemExit("Network contract violated: cleartext traffic must stay disabled")

java = root / "java/com/pocketmind/tools"
remote_client = root / "java/com/pocketmind/remote/RemoteSessionClient.kt"
allowed = {
    java / "LiveWebSearch.kt",
    java / "PublicApiClient.kt",
    remote_client,
}
for source in (root / "java").rglob("*.kt"):
    content = source.read_text(encoding="utf-8")
    markers = ("HttpsURLConnection", "HttpURLConnection", "Socket(", "newWebSocket(", ".openConnection(")
    if source not in allowed and any(marker in content for marker in markers):
        raise SystemExit(f"Network contract violated: transport outside approved clients ({source})")

search = (java / "LiveWebSearch.kt").read_text(encoding="utf-8")
api = (java / "PublicApiClient.kt").read_text(encoding="utf-8")
runtime = (java / "ToolRuntime.kt").read_text(encoding="utf-8")
models = (java / "ToolModels.kt").read_text(encoding="utf-8")
service = (root / "java/com/pocketmind/remote/RemoteSessionService.kt").read_text(encoding="utf-8")
manager = (root / "java/com/pocketmind/remote/RemoteSessionManager.kt").read_text(encoding="utf-8")
session_policy = (root / "java/com/pocketmind/remote/RemoteTestToolPolicy.kt").read_text(encoding="utf-8")
activity = (root / "java/com/pocketmind/ui/MainActivity.kt").read_text(encoding="utf-8")
if "HttpsURLConnection" not in search or "instanceFollowRedirects = false" not in search:
    raise SystemExit("Network contract violated: web search must use HTTPS without redirects")
if "HttpsURLConnection" not in api or "instanceFollowRedirects = false" not in api:
    raise SystemExit("Network contract violated: API client must use HTTPS without redirects")
if "fun get(urlText: String)" not in api or "readBounded" not in api:
    raise SystemExit("Network contract violated: API GET must be bounded")
if "isLoopbackAddress" not in api or "isSiteLocalAddress" not in api or "queryParameterNames" not in api:
    raise SystemExit("Network contract violated: API GET must reject private addresses and credential-like query parameters")
if runtime.count('"web.search" -> webSearch(step)') != 1 or runtime.count("LiveWebSearch.search(") != 1:
    raise SystemExit("Network contract violated: search transport must be called only by web.search")
if runtime.count('"api.discover" -> apiDiscover(step)') != 1 or runtime.count('"api.get" -> apiGet(step)') != 1:
    raise SystemExit("Network contract violated: API transport must be reachable only through registered API tools")
if "api.discover" not in models or "api.get" not in models:
    raise SystemExit("Network contract violated: API tools must be declared and validated")

client_source = remote_client.read_text(encoding="utf-8")
if "require(relay.isHttps)" not in client_source or "equals(\"https\"" not in client_source or 'scheme("http")' in client_source:
    raise SystemExit("Network contract violated: remote-test transport must require HTTPS/WSS")
if service.count("RemoteSessionClient(") != 1 or "startForeground(" not in service:
    raise SystemExit("Remote-test transport must be owned by its visible foreground service")
if "fun start(context: Context, relayUrl: String)" not in manager or "SecureRandom()" not in manager:
    raise SystemExit("Remote-test session must use a user-started, random in-memory pairing code")
if "PixelCopy.request(window" not in activity or "MediaProjection" in activity:
    raise SystemExit("Remote-test capture must remain limited to PocketMind's own app window")
if "decor.dispatchTouchEvent(event)" not in activity or "canAcceptRemoteInput()" not in activity:
    raise SystemExit("Remote-test control must be dispatched only to PocketMind's own views")
if "Intent.ACTION_SCREEN_OFF" not in service or "!appForeground" not in manager or "!ready || !visible" not in client_source:
    raise SystemExit("Remote-test transport must pause frames/control outside foreground and stop on screen lock")
if "RemoteTestToolPolicy.allows(remoteSessionRequest" not in runtime or "RemoteSessionManager.active" not in runtime:
    raise SystemExit("Remote-test requests must use normal tools only while their session is active")
if "!requestStartedInRemoteSession || sessionActiveAtExecution" not in session_policy:
    raise SystemExit("Remote-test plans must retain request origin and be rejected after Stop")
if "RemoteTestToolPolicy.allows(true, true)" not in (root / "../test/java/com/pocketmind/remote/RemoteTestToolPolicyTest.kt").resolve().read_text(encoding="utf-8"):
    raise SystemExit("Remote-test contract must cover normal PocketMind action parity")
android_ns = "{http://schemas.android.com/apk/res/android}"
manifest_root = ET.fromstring(manifest)
remote_services = [node for node in manifest_root.find("application") if node.attrib.get(android_ns + "name") == ".remote.RemoteSessionService"]
if "AccessibilityService" in manifest or len(remote_services) != 1 or remote_services[0].attrib.get(android_ns + "exported") != "false":
    raise SystemExit("Remote-test feature must not add an exported/global-control service")

print("OK: approved HTTPS tools plus explicit user-started WSS; remote capture/control stays inside PocketMind")
