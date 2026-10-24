#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/vendor/llama.cpp"
PIN="ba0ba54d93b25faf1e149f4ccedd3e9d84798563"
URL="https://github.com/ggml-org/llama.cpp.git"

if [[ -d "$DEST/.git" ]]; then
  git -C "$DEST" fetch --depth=1 origin "$PIN"
else
  mkdir -p "$(dirname "$DEST")"
  git init "$DEST"
  git -C "$DEST" remote add origin "$URL"
  git -C "$DEST" fetch --depth=1 origin "$PIN"
fi
git -C "$DEST" checkout --detach FETCH_HEAD
ACTUAL="$(git -C "$DEST" rev-parse HEAD)"
if [[ "$ACTUAL" != "$PIN" ]]; then
  echo "llama.cpp pin mismatch: expected $PIN, got $ACTUAL" >&2
  exit 1
fi
echo "llama.cpp pinned at $ACTUAL"
