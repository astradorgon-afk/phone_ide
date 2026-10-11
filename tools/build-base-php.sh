#!/usr/bin/env bash
#
# Builds and packages the Base and PHP toolchain bundles for one architecture.
#
# Runs on the HOST. Base and PHP are built together because PHP's dependency closure contains
# Base's: `termux-build.sh` rebuilds every dependency from source on each run (the install prefix
# is deliberately not persisted — see the long comment there), so building them apart would
# compile openssl, libcurl and the rest twice. Composer is a separate, fast build
# (`build-composer.sh`); the three install in the order base -> php -> composer.
#
# Base is not a package called "base", and that shapes two things. Its licence and source URL are
# passed literally instead of `auto` (`auto` reads `packages/<id>/build.sh`, and there is no
# `packages/base`); PHP's are `auto` and resolve from `packages/php`. And its closure roots are
# four, not one: `termux-tools`, plus `bash` (the shell the prefix needs, which `termux-tools`
# does not depend on) and `libmount`/`libblkid` (util-linux's `lsns` and friends link them, but
# util-linux does not pull them in through `Depends`). A closure of `termux-tools` alone packages
# cleanly and then fails the shared-library check — so `bash` is named, and built, explicitly.
#
# Prerequisites: as build-composer.sh (Docker with the termux package-builder image, and a
# `mf-termux` volume holding the termux-packages tree with the prefix repoint applied).
#
# Usage:
#   bash tools/build-base-php.sh <arch>
#
# Where <arch> is one of: x86_64, aarch64, arm, i686.
#
# Output: build/toolchain-bundles/{base-1.0.0,php-<version>}-<abi>.{zip,json}
#
# On Windows PowerShell this script cannot run as-is (the docker volumes need Windows paths), so
# invoke docker directly — this is what the repository used to produce the aarch64 bundles, and
# what the script below does step for step:
#
#   $repo = (Get-Location).Path
#   docker run -d --name mf-aarch64 --device /dev/fuse --cap-add CAP_SYS_ADMIN `
#     --security-opt apparmor=unconfined -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge -e MF_JOBS=3 `
#     -v mf-termux:/work -v "$repo\tools:/tools:ro" -v "$repo\build\toolchain-bundles:/out" `
#     ghcr.io/termux/package-builder:latest bash -c `
#       "bash /tools/termux-build.sh aarch64 termux-tools bash php && cd /work/termux-packages && \
#        PROVIDE_SH=bash bash /tools/package-termux-bundle.sh base 'MobileForge Base' 1.0.0 aarch64 GPL-3.0 https://github.com/termux/termux-packages --closure output/ termux-tools,bash,libmount,libblkid && \
#        REQUIRES=base bash /tools/package-termux-bundle.sh php 'PHP' 8.5.1 aarch64 auto auto --closure output/ php && \
#        cp /work/bundles/base-1.0.0-arm64-v8a.* /work/bundles/php-8.5.1-arm64-v8a.* /out/"
#
# The build (step 1) is hours; the packaging (step 2) is seconds once the .debs exist.

set -euo pipefail

ARCH="${1:-}"
if [ -z "$ARCH" ]; then
    echo "usage: build-base-php.sh <arch>" >&2
    echo "  arch: x86_64, aarch64, arm, i686" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUNDLE_DIR="$ROOT/build/toolchain-bundles"
mkdir -p "$BUNDLE_DIR"

# Map termux arch to Android ABI (the manifest records the ABI, not the termux name).
case "$ARCH" in
    x86_64)  ABI="x86_64" ;;
    aarch64) ABI="arm64-v8a" ;;
    arm)     ABI="armeabi-v7a" ;;
    i686)    ABI="x86" ;;
    *) echo "ERROR: unknown arch '$ARCH'" >&2; exit 2 ;;
esac

BASE_VERSION="1.0.0"
PHP_VERSION="8.5.1"
IMAGE="ghcr.io/termux/package-builder:latest"
DOCKER_ARGS=(--rm --device /dev/fuse --cap-add CAP_SYS_ADMIN
    --security-opt apparmor=unconfined -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge
    -v mf-termux:/work -v "$ROOT/tools:/tools:ro")

echo "==> Building Base $BASE_VERSION (termux-tools closure) + PHP $PHP_VERSION for $ABI"

echo "==> Step 0/3: Applying termux fork (prefix repoint)"
docker run "${DOCKER_ARGS[@]}" "$IMAGE" bash /tools/apply-termux-fork.sh

echo "==> Step 1/3: Compiling termux-tools + bash + php for $ARCH (hours)"
docker run "${DOCKER_ARGS[@]}" -e MF_JOBS="${MF_JOBS:-4}" "$IMAGE" \
    bash /tools/termux-build.sh "$ARCH" termux-tools bash php

echo "==> Step 2/3: Packaging base + php"
docker run "${DOCKER_ARGS[@]}" -v "$BUNDLE_DIR:/out" "$IMAGE" bash -c "
    cd /work/termux-packages &&
    PROVIDE_SH=bash bash /tools/package-termux-bundle.sh \
        base 'MobileForge Base' $BASE_VERSION $ARCH GPL-3.0 \
        https://github.com/termux/termux-packages \
        --closure output/ termux-tools,bash,libmount,libblkid &&
    REQUIRES=base bash /tools/package-termux-bundle.sh \
        php 'PHP' $PHP_VERSION $ARCH auto auto --closure output/ php &&
    cp /work/bundles/base-$BASE_VERSION-$ABI.zip /work/bundles/base-$BASE_VERSION-$ABI.json \
       /work/bundles/php-$PHP_VERSION-$ABI.zip /work/bundles/php-$PHP_VERSION-$ABI.json /out/
"

echo "==> Step 3/3: Done"
ls -la "$BUNDLE_DIR"/base-"$BASE_VERSION"-"$ABI".* "$BUNDLE_DIR"/php-"$PHP_VERSION"-"$ABI".*
