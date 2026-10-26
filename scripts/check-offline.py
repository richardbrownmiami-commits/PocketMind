#!/usr/bin/env python3
"""Enforce PocketMind's strictly on-device, no-network runtime contract."""
from pathlib import Path

root = Path("app/src/main")
manifest = (root / "AndroidManifest.xml").read_text(encoding="utf-8")
if 'android.permission.INTERNET' in manifest:
    raise SystemExit("Offline contract violated: INTERNET permission must not be declared")
if 'android:usesCleartextTraffic="false"' not in manifest:
    raise SystemExit("Offline contract violated: cleartext traffic must stay disabled")

network_markers = ("HttpsURLConnection", "HttpURLConnection", "Socket(", "WebSocket", ".openConnection(")
for source in (root / "java").rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    if any(marker in text for marker in network_markers):
        raise SystemExit(f"Offline contract violated: network transport found in {source}")

for forbidden in (
    root / "java/com/pocketmind/tools/LiveWebSearch.kt",
    root / "java/com/pocketmind/tools/LiveSearchPlannerContext.kt",
):
    if forbidden.exists():
        raise SystemExit(f"Offline contract violated: online search component remains ({forbidden})")

runtime = (root / "java/com/pocketmind/tools/ToolRuntime.kt").read_text(encoding="utf-8")
if "LiveWebSearch" in runtime or '"web.search"' in runtime:
    raise SystemExit("Offline contract violated: network search remains in the tool runtime")

controller = (root / "java/com/pocketmind/core/AgentController.kt").read_text(encoding="utf-8")
required_agent_guards = (
    "private const val MAX_AGENT_STEPS = 4",
    "executeOfflineAgentPlan",
    "if (result.status != ActionStatus.VERIFIED) break",
    "executed.any { stepSignature(it) == stepSignature(step) }",
    "Live web search is disabled in strict offline mode",
)
for guard in required_agent_guards:
    if guard not in controller:
        raise SystemExit(f"Offline agent contract violated: missing guard {guard!r}")

print("OK: no network transport; offline agent is bounded, verified, and duplicate-safe")
