#!/usr/bin/env bash
#
# Builds and packages the nginx toolchain bundle.
#
# Runs on the HOST. Drives the termux package-builder container to compile nginx from source and
# package it as a MobileForge toolchain bundle, exactly like build-composer.sh does for Composer.
#
# Why nginx at all, when `php artisan serve` already works?
#
# `artisan serve` runs PHP's built-in server (a single process, no concurrency) and is meant for
# development. nginx is what someone reaches for when they want a real front end — static files
# served without booting PHP, concurrency, keep-alive. The two are not exclusive: the Artisan
# pane keeps `serve` for the quick loop, and this bundle exists for the people who outgrow it.
#
# The FastCGI side needs no new bundle. The `php` bundle already ships `bin/php-cgi`, which is a
# complete FastCGI SAPI:
#
#     php-cgi -b 127.0.0.1:9000
#
# so nginx is pointed at that. `php-fpm` is deliberately NOT built here: the termux `php` recipe
# in this tree does not install the FPM SAPI (see tools/nginx/laravel.conf for the arrangement we
# do use), and a process manager is not required for a single-site dev server. If FPM is ever
# wanted, it is a change to the php recipe plus a php rebuild, not a change here.
#
# Prerequisites: Docker with ghcr.io/termux/package-builder:latest, and a `mf-termux` volume with
# the termux-packages tree (see build-composer.sh for the one-time setup).
#
# Usage:
#   bash tools/build-nginx.sh <arch>
#
# Where <arch> is one of: x86_64, aarch64, arm, i686.
#
# Output: build/toolchain-bundles/nginx-<version>-<abi>.{zip,json}
#
# On Windows PowerShell this script cannot run as-is (the docker volumes want Windows paths), so
# invoke docker directly — this is the equivalent of build-composer.sh's documented form:
#
#   $repo = (Get-Location).Path
#   docker run --rm --device /dev/fuse --cap-add CAP_SYS_ADMIN `
#     --security-opt apparmor=unconfined -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge `
#     -v mf-termux:/work -v "$repo\tools:/tools:ro" -v "$repo\build\toolchain-bundles:/out" `
#     ghcr.io/termux/package-builder:latest bash -c `
#       "bash /tools/apply-termux-fork.sh && bash /tools/termux-build.sh x86_64 nginx && \
#        cd /work/termux-packages && REQUIRES=base bash /tools/package-termux-bundle.sh \
#          nginx 'nginx' <version> x86_64 auto auto --closure output/ nginx && \
#        cp /work/bundles/nginx-<version>-x86_64.* /out/"
#
# The compile is the slow part (nginx pulls openssl, pcre2 and zlib through its closure); the
# packaging is seconds once the .debs exist.

set -euo pipefail

ARCH="${1:-}"
if [ -z "$ARCH" ]; then
    echo "usage: build-nginx.sh <arch>" >&2
    echo "  arch: x86_64, aarch64, arm, i686" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUNDLE_DIR="$ROOT/build/toolchain-bundles"
mkdir -p "$BUNDLE_DIR"

case "$ARCH" in
    x86_64)  ABI="x86_64" ;;
    aarch64) ABI="arm64-v8a" ;;
    arm)     ABI="armeabi-v7a" ;;
    i686)    ABI="x86" ;;
    *) echo "ERROR: unknown arch '$ARCH'" >&2; exit 2 ;;
esac

# Kept in step with the termux recipe; packaging reads the built version anyway, so this is only
# used for the output file name and is verified against the .deb below.
VERSION="1.31.6"
BUNDLE_ID="nginx"
DISPLAY_NAME="nginx"
IMAGE="ghcr.io/termux/package-builder:latest"
DOCKER_ARGS=(--rm --device /dev/fuse --cap-add CAP_SYS_ADMIN
    --security-opt apparmor=unconfined -e TERMUX_APP__PACKAGE_NAME=dev.mobileforge
    -v mf-termux:/work -v "$ROOT/tools:/tools:ro")

echo "==> Building nginx $VERSION for $ABI"

echo "==> Step 0/3: Applying termux fork (prefix repoint)"
docker run "${DOCKER_ARGS[@]}" "$IMAGE" bash /tools/apply-termux-fork.sh

echo "==> Step 1/3: Compiling nginx + closure for $ARCH (slow: openssl, pcre2, zlib)"
docker run "${DOCKER_ARGS[@]}" -e MF_JOBS="${MF_JOBS:-4}" "$IMAGE" \
    bash /tools/termux-build.sh "$ARCH" nginx

echo "==> Step 2/3: Packaging nginx"
docker run "${DOCKER_ARGS[@]}" -v "$BUNDLE_DIR:/out" "$IMAGE" bash -c "
    cd /work/termux-packages &&
    REQUIRES=base bash /tools/package-termux-bundle.sh \
        $BUNDLE_ID '$DISPLAY_NAME' $VERSION $ARCH auto auto \
        --closure output/ nginx &&
    cp /work/bundles/$BUNDLE_ID-$VERSION-$ABI.zip /work/bundles/$BUNDLE_ID-$VERSION-$ABI.json /out/
"

echo "==> Step 3/3: Done"
ls -la "$BUNDLE_DIR/$BUNDLE_ID-$VERSION-$ABI".*
