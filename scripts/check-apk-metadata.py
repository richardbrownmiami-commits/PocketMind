#!/usr/bin/env python3
"""Verify app identity and offline/API contract embedded in a built APK."""
import argparse
import os
import re
import shutil
import subprocess
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--application-id", default="com.pocketmind")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    args = parser.parse_args()

    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    candidates = [sdk / "build-tools" / "35.0.0" / "aapt", Path(shutil.which("aapt") or "")]
    aapt = next((candidate for candidate in candidates if candidate.is_file()), None)
    if aapt is None:
        raise SystemExit("Could not find aapt from Android build-tools 35.0.0")

    badging = subprocess.check_output([str(aapt), "dump", "badging", str(args.apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if package is None:
        raise SystemExit("APK manifest has no readable package/version metadata")
    actual_package, code, name = package.groups()
    if actual_package != args.application_id:
        raise SystemExit(f"Wrong applicationId in APK: {actual_package}")
    if int(code) != args.version_code or name != args.version_name:
        raise SystemExit(f"Wrong APK version: found {name} ({code}), expected {args.version_name} ({args.version_code})")
    if not re.search(r"^sdkVersion:'30'$", badging, re.M):
        raise SystemExit("APK minSdkVersion must be 30")
    if not re.search(r"^targetSdkVersion:'30'$", badging, re.M):
        raise SystemExit("APK targetSdkVersion must be 30")

    permissions = subprocess.check_output([str(aapt), "dump", "permissions", str(args.apk)], text=True)
    if "android.permission.INTERNET" in permissions:
        raise SystemExit("APK unexpectedly requests android.permission.INTERNET")

    print(f"APK manifest verified: {actual_package} {name} ({code}), min/target API 30, no INTERNET permission")


if __name__ == "__main__":
    main()
