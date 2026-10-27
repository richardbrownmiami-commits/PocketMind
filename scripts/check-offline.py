#!/usr/bin/env python3
"""Keep app networking limited to explicit live-search tool execution."""
from pathlib import Path

root = Path("app/src/main")
manifest = (root / "AndroidManifest.xml").read_text(encoding="utf-8")
permission = '<uses-permission android:name="android.permission.INTERNET" />'
if manifest.count(permission) != 1:
    raise SystemExit("Network contract violated: declare INTERNET exactly once for web.search")
if 'android:usesCleartextTraffic="false"' not in manifest:
    raise SystemExit("Network contract violated: cleartext traffic must stay disabled")

search_client = root / "java/com/pocketmind/tools/LiveWebSearch.kt"
if not search_client.is_file() or "HttpsURLConnection" not in search_client.read_text(encoding="utf-8"):
    raise SystemExit("Network contract violated: HTTPS transport must stay in LiveWebSearch.kt")

network_markers = ("HttpsURLConnection", "HttpURLConnection", "Socket(", "WebSocket", ".openConnection(")
for source in (root / "java").rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    if source != search_client and any(marker in text for marker in network_markers):
        raise SystemExit(f"Network contract violated: network transport found outside LiveWebSearch.kt ({source})")

runtime = (root / "java/com/pocketmind/tools/ToolRuntime.kt").read_text(encoding="utf-8")
if runtime.count('"web.search" -> webSearch(step)') != 1 or runtime.count("LiveWebSearch.search(") != 1:
    raise SystemExit("Network contract violated: LiveWebSearch must be called only by the web.search tool")

print("OK: HTTPS networking is scoped to the explicit web.search tool")
