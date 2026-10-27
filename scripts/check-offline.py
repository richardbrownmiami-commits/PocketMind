#!/usr/bin/env python3
"""Keep network access limited to explicit read-only online tools."""
from pathlib import Path

root = Path("app/src/main")
permission = '<uses-permission android:name="android.permission.INTERNET" />'
manifest = (root / "AndroidManifest.xml").read_text(encoding="utf-8")
if manifest.count(permission) != 1:
    raise SystemExit("Network contract violated: INTERNET must be declared exactly once")
if 'android:usesCleartextTraffic="false"' not in manifest:
    raise SystemExit("Network contract violated: cleartext traffic must stay disabled")

java = root / "java/com/pocketmind/tools"
allowed = {
    java / "LiveWebSearch.kt",
    java / "PublicApiClient.kt",
}
for source in (root / "java").rglob("*.kt"):
    content = source.read_text(encoding="utf-8")
    markers = ("HttpsURLConnection", "HttpURLConnection", "Socket(", "WebSocket", ".openConnection(")
    if source not in allowed and any(marker in content for marker in markers):
        raise SystemExit(f"Network contract violated: transport outside approved clients ({source})")

search = (java / "LiveWebSearch.kt").read_text(encoding="utf-8")
api = (java / "PublicApiClient.kt").read_text(encoding="utf-8")
runtime = (java / "ToolRuntime.kt").read_text(encoding="utf-8")
models = (java / "ToolModels.kt").read_text(encoding="utf-8")
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

print("OK: HTTPS network access is limited to web.search, api.discover, and bounded api.get")
