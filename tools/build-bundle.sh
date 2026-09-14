#!/usr/bin/env bash
#
# Builds a MobileForge toolchain bundle from tools/bundle/*.c.
#
# This is the reference implementation of the bundle format described in
# docs/adr/ADR-011-toolchain-strategy.md. It exists to prove the packaging and install pipeline
# with a binary we control, before investing in packaging real software.
#
# The critical build flags:
#   -fPIE -pie                    produces ET_DYN, which is what the system linker can load.
#                                 A non-PIE ET_EXEC binary cannot be linker-exec'd at all.
#   -Wl,-z,max-page-size=16384    16 KB alignment, required by newer Android devices and
#                                 harmless on 4 KB ones (RISK-004).
#
# Usage:  bash tools/build-bundle.sh [output-dir]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/build/bundles}"

NDK_VERSION="27.0.12077973"
NDK_BIN="${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$NDK_BIN" ] || NDK_BIN="/c/Users/$USER/AppData/Local/Android/Sdk/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/windows-x86_64/bin"

if [ ! -d "$NDK_BIN" ]; then
  echo "ERROR: NDK $NDK_VERSION not found. Set ANDROID_SDK_ROOT." >&2
  exit 1
fi

# The prefix is baked into the manifest and checked at install time. It is the app's own
# private directory: bundles are NOT relocatable between apps, which is precisely why Termux
# packages cannot be reused (ADR-011).
APP_ID="dev.mobileforge"
PREFIX="/data/data/$APP_ID/files/usr"
VERSION="1.0.0"

mkdir -p "$OUT"

build_for() {
  local abi="$1" triple="$2"
  local stage="$OUT/stage-$abi"

  rm -rf "$stage"
  mkdir -p "$stage/bin"

  echo "==> Compiling mf-doctor for $abi"
  "$NDK_BIN/$triple-clang.cmd" \
    -O2 -fPIE -pie \
    -Wl,-z,max-page-size=16384 \
    -o "$stage/bin/mf-doctor" \
    "$ROOT/tools/bundle/mf-doctor.c"

  local archive="$OUT/mf-doctor-$VERSION-$abi.zip"
  rm -f "$archive"
  # python rather than `zip`: not every dev machine has the zip binary, and Python is
  # already required for other tooling here.
  python -c "import shutil,sys; shutil.make_archive(sys.argv[1][:-4], 'zip', sys.argv[2])" "$archive" "$stage"

  local sha
  sha=$(sha256sum "$archive" | cut -d' ' -f1)
  local size
  size=$(stat -c %s "$archive")

  cat > "$OUT/mf-doctor-$VERSION-$abi.json" <<JSON
{
  "id": "mf-doctor",
  "display_name": "MobileForge Doctor",
  "version": "$VERSION",
  "abi": "$abi",
  "prefix": "$PREFIX",
  "sha256": "$sha",
  "size_bytes": $size,
  "page_size": 16384,
  "executables": ["bin/mf-doctor"],
  "license": "Apache-2.0",
  "source_url": "https://example.invalid/mobileforge/tools/bundle/mf-doctor.c"
}
JSON

  echo "    $archive"
  echo "    sha256 $sha  ($size bytes)"
  rm -rf "$stage"
}

build_for "arm64-v8a" "aarch64-linux-android30"
build_for "x86_64" "x86_64-linux-android30"
build_for "armeabi-v7a" "armv7a-linux-androideabi30"

echo
echo "Bundles written to $OUT"
