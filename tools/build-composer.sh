#!/usr/bin/env bash
#
# Builds and packages the Composer toolchain bundle.
#
# Runs on the HOST. Drives the termux package-builder container to:
#   1. Apply the Composer package recipe
#   2. Build Composer inside the container
#   3. Package it into a MobileForge toolchain bundle
#
# Prerequisites:
#   - Docker with the ghcr.io/termux/package-builder:latest image
#   - A Docker volume named mf-termux with the termux-packages source tree
#     (clone it once: git clone https://github.com/termux/termux-packages,
#     then chown the volume to the image's builder user)
#
# The prefix repoint is verified in scripts/properties.sh: TERMUX_APP__PACKAGE_NAME
# is a sanctioned fork variable, and packaging refuses a .deb laid out under any
# other prefix — so a wrong value fails loudly here, not on a device.
#
# On Windows PowerShell, invoke docker directly (this script is for bash hosts):
#   docker run --rm --device /dev/fuse --cap-add CAP_SYS_ADMIN `
#     --security-opt apparmor=unconfined `
#     -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge `
#     -v mf-termux:/work -v <repo>/tools:/tools:ro `
#     ghcr.io/termux/package-builder:latest `
#     bash -c 'bash /tools/apply-composer-package.sh && bash /tools/termux-build.sh x86_64 composer'
#
# Usage:
#   bash tools/build-composer.sh <arch>
#
# Where <arch> is one of: x86_64, aarch64, arm, i686
#
# The bundle is written to build/toolchain-bundles/composer-<version>-<abi>.zip
# with a corresponding .json manifest.

set -euo pipefail

ARCH="${1:-}"
if [ -z "$ARCH" ]; then
    echo "usage: build-composer.sh <arch>" >&2
    echo "  arch: x86_64, aarch64, arm, i686" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUNDLE_DIR="$ROOT/build/toolchain-bundles"
mkdir -p "$BUNDLE_DIR"

# Map termux arch to Android ABI
case "$ARCH" in
    x86_64)  ABI="x86_64" ;;
    aarch64) ABI="arm64-v8a" ;;
    arm)     ABI="armeabi-v7a" ;;
    i686)    ABI="x86" ;;
    *) echo "ERROR: unknown arch '$ARCH'" >&2; exit 2 ;;
esac

VERSION="2.10.3"
BUNDLE_ID="composer"
DISPLAY_NAME="Composer"
LICENSE="MIT"
SOURCE_URL="https://github.com/composer/composer"

echo "==> Building Composer $VERSION for $ABI"

# Step 0: Repoint the source tree at our prefix (once per fresh volume; idempotent)
echo "==> Step 0/4: Applying termux fork (prefix repoint)"
docker run --rm --device /dev/fuse --cap-add CAP_SYS_ADMIN \
    --security-opt apparmor=unconfined -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge \
    -v mf-termux:/work \
    -v "$ROOT/tools:/tools:ro" ghcr.io/termux/package-builder:latest \
    bash /tools/apply-termux-fork.sh

# Step 1: Apply the package recipe and build inside the container
echo "==> Step 1/4: Building $BUNDLE_ID $VERSION for $ARCH"
docker run --rm --device /dev/fuse --cap-add CAP_SYS_ADMIN \
    --security-opt apparmor=unconfined -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge \
    -v mf-termux:/work \
    -v "$ROOT/tools:/tools:ro" ghcr.io/termux/package-builder:latest \
    bash -c "bash /tools/apply-composer-package.sh && bash /tools/termux-build.sh $ARCH composer"

# Step 2: Package the .deb output into a bundle
echo "==> Step 2/4: Packaging $BUNDLE_ID $VERSION for $ABI"
docker run --rm --device /dev/fuse --cap-add CAP_SYS_ADMIN \
    --security-opt apparmor=unconfined -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge \
    -v mf-termux:/work \
    -v "$ROOT/tools:/tools:ro" -v "$BUNDLE_DIR:/out" ghcr.io/termux/package-builder:latest \
    bash -c "cd /work/termux-packages && REQUIRES='base,php' REQUIRES_PROVIDES='php' \
        bash /tools/package-termux-bundle.sh \
        $BUNDLE_ID $DISPLAY_NAME $VERSION $ARCH auto auto \
        --closure output/ composer"

# Step 3: Copy the bundle to the build directory
echo "==> Step 3/4: Copying bundle to $BUNDLE_DIR"
docker run --rm -v mf-termux:/work -v "$BUNDLE_DIR:/out" \
    ghcr.io/termux/package-builder:latest \
    bash -c "cp /work/bundles/$BUNDLE_ID-$VERSION-$ABI.zip /work/bundles/$BUNDLE_ID-$VERSION-$ABI.json /out/"

echo "==> Done: $BUNDLE_DIR/$BUNDLE_ID-$VERSION-$ABI.zip"
ls -la "$BUNDLE_DIR/$BUNDLE_ID-$VERSION-$ABI".*
