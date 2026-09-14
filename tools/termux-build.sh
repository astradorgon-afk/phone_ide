#!/usr/bin/env bash
#
# Builds MobileForge toolchain packages inside the termux package-builder container.
#
# Runs INSIDE the container. See docs/adr/ADR-011-toolchain-strategy.md.
#
# Why this exists rather than upstream's scripts/run-docker.sh: that script builds POSIX paths
# for --volume and --security-opt, which Docker Desktop on Windows cannot resolve. This drives
# the container directly instead.
#
# Only `~/.termux-build` — downloaded sources and per-package build directories — is persisted
# into the Docker volume. The install prefix deliberately is NOT; see the long comment below,
# which records what happened when it was.
#
# Consequence: every run rebuilds its dependency tree from source. Since the prefix is repointed,
# prebuilt upstream packages cannot be downloaded either (that is the point of ADR-011), so for
# PHP or Node this is hours. That cost is accepted.
#
# Usage (from the host):
#   docker run --rm --device /dev/fuse --cap-add CAP_SYS_ADMIN \
#     --security-opt apparmor=unconfined -v mf-termux:/work \
#     -v "<repo>/tools:/tools:ro" ghcr.io/termux/package-builder:latest \
#     bash /tools/termux-build.sh x86_64 php
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "usage: termux-build.sh <arch> <package>..." >&2
    exit 2
fi

ARCH="$1"; shift
PACKAGES=("$@")

WORK="${WORK:-/work}"
SRC="$WORK/termux-packages"
ROOTFS="/data/data/dev.mobileforge"

# State is per-architecture, and must be.
#
# termux-packages does support switching arch in one tree — it moves /data/data aside into a
# per-arch backup and restores the other. That mechanism is defeated here, because the prefix is
# a symlink into the volume: both architectures would resolve to the same directory and quietly
# mix aarch64 and x86_64 libraries. Two builds running at once would corrupt each other outright.
#
# Separate directories make the isolation structural rather than something to remember.
#
STATE="$WORK/state/$ARCH"

[ -d "$SRC" ] || { echo "ERROR: no source tree at $SRC" >&2; exit 1; }

mkdir -p "$STATE/termux-build"

# Redirect the build-work directory into the volume.
if [ ! -L "$HOME/.termux-build" ]; then
    rm -rf "$HOME/.termux-build"
    ln -s "$STATE/termux-build" "$HOME/.termux-build"
fi

# The INSTALL PREFIX IS DELIBERATELY NOT PERSISTED. This was tried and it corrupted packages.
#
# termux determines a package's contents by diffing the prefix around the build. With a prefix
# carried over from earlier builds, files belonging to other packages get attributed to whatever
# is being rebuilt: an `openssl` .deb produced this way contained 1453 apache2 files, 112
# apache2 modules and capstone's headers. Nothing downstream catches that — the bundle installs
# and the binaries are the right architecture; it is simply the wrong contents.
#
# The cost of a clean prefix is rebuilding dependencies that are already compiled. That is
# hours, and it is the correct trade: a fast build producing wrong packages is worth nothing.
#
# Only the source/work cache is persisted, which is safe — it holds downloaded tarballs and
# per-package build directories, and nothing reads it to decide what a package contains.

cd "$SRC"

for package in "${PACKAGES[@]}"; do
    echo "=============================================================="
    echo "==> Building $package for $ARCH"
    echo "=============================================================="
    log="$WORK/$package-$ARCH.log"

    # NOTE: no -I / -i. Upstream prebuilt packages carry com.termux's prefix baked in and are
    # unusable here; build-package.sh refuses them anyway once the app package name differs.
    #
    # MF_JOBS limits parallelism so a second architecture can build alongside the first without
    # either starving. Unset means build-package.sh uses all cores.
    if ./build-package.sh -a "$ARCH" ${MF_JOBS:+-j "$MF_JOBS"} "$package" > "$log" 2>&1; then
        echo "==> $package OK"
        ls -la output/ | grep -E "^-.*${package}_" || true
    else
        status=$?
        echo "==> $package FAILED (exit $status). Last 30 lines:" >&2
        tail -30 "$log" >&2
        exit "$status"
    fi
done

echo "==> All requested packages built"
ls -la "$SRC/output/" | tail -20
