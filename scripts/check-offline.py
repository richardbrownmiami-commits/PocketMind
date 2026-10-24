#!/usr/bin/env python3
from pathlib import Path

manifest = Path("app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
if "android.permission.INTERNET" in manifest:
    raise SystemExit("Offline policy violated: INTERNET permission is declared")
print("OK: no INTERNET permission in the app manifest")
