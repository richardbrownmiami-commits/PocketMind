#!/usr/bin/env python3
import sys
import zipfile

apk = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk"
with zipfile.ZipFile(apk) as archive:
    libraries = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
if not libraries:
    raise SystemExit("APK contains no native shared libraries")
wrong = [name for name in libraries if not name.startswith("lib/armeabi-v7a/")]
if wrong:
    raise SystemExit("Non-ARMv7 native libraries found: " + ", ".join(wrong))
if "lib/armeabi-v7a/libpocketmind.so" not in libraries:
    raise SystemExit("APK is missing libpocketmind.so for armeabi-v7a")
print(f"OK: {len(libraries)} native libraries, all armeabi-v7a")
