#!/usr/bin/env bash
#
# Vendors the Monaco editor into feature/editor/src/main/assets/monaco/.
#
# Monaco is fetched rather than committed: it is ~5 MB of third-party build output, and a
# repository is not a CDN. The result IS bundled into the APK, because the IDE is offline-first
# (ADR-003) and an editor that needs the network to open a file would defeat the point.
#
# Monaco is MIT-licensed; LICENSE.txt is copied alongside the code to satisfy attribution.
#
# Usage:  bash tools/fetch-monaco.sh [version]

set -euo pipefail

VERSION="${1:-0.52.2}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/feature/editor/src/main/assets/monaco"
STAGING="$(mktemp -d)"

cleanup() { rm -rf "$STAGING"; }
trap cleanup EXIT

echo "Fetching monaco-editor@$VERSION ..."
cd "$STAGING"
npm pack "monaco-editor@$VERSION" --silent >/dev/null
tar -xzf monaco-editor-"$VERSION".tgz

SRC="$STAGING/package/min/vs"
if [ ! -d "$SRC" ]; then
  echo "ERROR: expected $SRC in the npm package; layout may have changed." >&2
  exit 1
fi

rm -rf "$DEST/vs"
mkdir -p "$DEST"
cp -r "$SRC" "$DEST/vs"
cp "$STAGING/package/LICENSE" "$DEST/LICENSE.txt" 2>/dev/null || \
  cp "$STAGING/package/ThirdPartyNotices.txt" "$DEST/LICENSE.txt" 2>/dev/null || true

printf '%s\n' "$VERSION" > "$DEST/VERSION"

echo "Monaco $VERSION installed at $DEST"
du -sh "$DEST" 2>/dev/null || true
