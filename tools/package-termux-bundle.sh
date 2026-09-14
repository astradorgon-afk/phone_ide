#!/usr/bin/env bash
#
# Packages termux-packages .deb output into a MobileForge toolchain bundle.
#
# Runs INSIDE the termux package-builder container, because it needs `dpkg-deb` and the build
# output. See docs/adr/ADR-011-toolchain-strategy.md for the format and why bundles are not
# relocatable between apps.
#
# The .debs lay files out under an absolute path — /data/data/dev.mobileforge/files/usr/... —
# because the prefix is baked in at build time. `ToolchainInstaller` extracts relative to
# $PREFIX, so that leading path is stripped here. Getting this wrong would install a working
# toolchain into $PREFIX/data/data/... where nothing can find it.
#
# Usage:
#   package-termux-bundle.sh <id> <display-name> <version> <termux-arch> <license> <source-url> <deb>...
#   package-termux-bundle.sh ... --closure <output-dir> <root-package>
#
# `--closure` resolves the package's full runtime dependency set from the .deb control files in
# <output-dir>, rather than trusting a hand-written list. Listing dependencies by hand is how a
# bundle ends up one shared library short — which surfaces on the device as the "library not
# found" failure ADR-011 exists to prevent, long after packaging.
set -euo pipefail

if [ "$#" -lt 7 ]; then
    sed -n '2,20p' "$0" >&2
    exit 2
fi

ID="$1"; DISPLAY_NAME="$2"; VERSION="$3"; TERMUX_ARCH="$4"; LICENSE="$5"; SOURCE_URL="$6"
shift 6

# Pass "auto" for the licence or source URL to read them from the package's own build.sh.
#
# Worth doing rather than typing them: `source_url` is a required manifest field precisely
# because several of these tools are GPL-licensed and distributing them carries an obligation to
# offer source. Typed by hand it drifts — git was first packaged here with the project homepage,
# which identifies the project but is not the source that produced these binaries.
read_package_field() {
    local package="$1" field="$2"
    local build_sh="packages/$package/build.sh"
    [ -f "$build_sh" ] || build_sh="root-packages/$package/build.sh"
    [ -f "$build_sh" ] || return 1

    local version
    version="$(sed -n 's/^TERMUX_PKG_VERSION=["'\'']\{0,1\}\([^"'\'']*\)["'\'']\{0,1\}.*/\1/p' \
        "$build_sh" | head -1)"
    local value
    value="$(sed -n "s/^$field=[\"']\{0,1\}\([^\"']*\)[\"']\{0,1\}.*/\1/p" "$build_sh" | head -1)"
    [ -n "$value" ] || return 1

    # The only expansion these fields use in practice.
    printf '%s' "${value//\$\{TERMUX_PKG_VERSION\}/$version}"
}

if [ "$LICENSE" = "auto" ]; then
    LICENSE="$(read_package_field "$ID" TERMUX_PKG_LICENSE)" ||
        { echo "ERROR: could not read licence for '$ID'" >&2; exit 1; }
    echo "==> licence from package definition: $LICENSE"
fi

if [ "$SOURCE_URL" = "auto" ]; then
    SOURCE_URL="$(read_package_field "$ID" TERMUX_PKG_SRCURL)" ||
        { echo "ERROR: could not read source URL for '$ID'" >&2; exit 1; }
    echo "==> source URL from package definition: $SOURCE_URL"
fi

if [ "${1:-}" = "--closure" ]; then
    [ "$#" -eq 3 ] || { echo "ERROR: --closure needs <output-dir> <root-package>" >&2; exit 2; }
    mapfile -t DEBS < <(python3 - "$2" "$3" "$TERMUX_ARCH" <<'PYTHON'
import os, re, subprocess, sys

output_dir, roots_arg, arch = sys.argv[1], sys.argv[2], sys.argv[3]

# Several roots, comma-separated.
#
# Needed because the closure follows `Depends` only. termux lists npm under `Recommends` for
# nodejs-lts (Node is built --without-npm), so a Depends-only walk produces a Node bundle with
# no npm in it — which is how "Node works" was briefly true while "Node/npm works" was not.
roots = [r for r in roots_arg.split(",") if r]

# One .deb per package name, FILTERED BY ARCHITECTURE.
#
# The filter is not optional: termux-packages writes every architecture into one output
# directory, so an unfiltered scan silently pairs an x86_64 package with aarch64 dependencies.
# That produced a PHP bundle with 14 AArch64 libraries in it, caught only by the ELF machine
# check further down — which is the sort of bundle that installs cleanly and then fails on the
# device with an unreadable linker error.
#
# `_all.deb` packages (certificates, scripts) carry no ELF and belong in every bundle.
suffixes = (f"_{arch}.deb", "_all.deb")

by_package = {}
for name in sorted(os.listdir(output_dir)):
    if not name.endswith(suffixes):
        continue
    path = os.path.join(output_dir, name)
    control = subprocess.run(
        ["dpkg-deb", "-f", path, "Package", "Depends"],
        capture_output=True, text=True, check=True,
    ).stdout
    fields = {}
    for line in control.splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
    package = fields.get("Package")
    if package and package not in by_package:
        by_package[package] = (path, fields.get("Depends", ""))

for root in roots:
    if root not in by_package:
        sys.exit(f"ERROR: no .deb for '{root}' in {output_dir}")

# Breadth-first over Depends. Alternatives ("a | b") take the first that exists locally;
# version constraints are stripped because the local build produced exactly one version.
seen, order, queue = set(), [], list(roots)
while queue:
    package = queue.pop(0)
    if package in seen or package not in by_package:
        continue
    seen.add(package)
    path, depends = by_package[package]
    order.append(path)
    for clause in depends.split(","):
        for alternative in clause.split("|"):
            name = re.sub(r"\(.*?\)", "", alternative).strip()
            if name in by_package:
                queue.append(name)
                break

print("\n".join(order))
PYTHON
    )
    echo "==> Dependency closure for $3: ${#DEBS[@]} package(s)"
else
    DEBS=("$@")
fi

# Licence and source for EVERY package in the bundle, not just the headline one.
#
# A PHP bundle carries 33 packages, several of them GPL or LGPL. The obligation to offer source
# attaches to each separately, so recording only `php`'s licence would leave the offer
# incomplete for everything underneath it.
COMPONENTS_FILE="$(mktemp)"
: > "$COMPONENTS_FILE"
for deb in "${DEBS[@]}"; do
    component="$(basename "$deb")"
    component="${component%%_*}"
    component_version="$(read_package_field "$component" TERMUX_PKG_VERSION || true)"
    component_license="$(read_package_field "$component" TERMUX_PKG_LICENSE || true)"
    component_source="$(read_package_field "$component" TERMUX_PKG_SRCURL || true)"
    # Subpackages (php-gd, git-gui) have no build.sh of their own; they inherit from the parent.
    if [ -z "$component_license" ]; then
        parent="${component%-*}"
        component_version="${component_version:-$(read_package_field "$parent" TERMUX_PKG_VERSION || true)}"
        component_license="$(read_package_field "$parent" TERMUX_PKG_LICENSE || true)"
        component_source="$(read_package_field "$parent" TERMUX_PKG_SRCURL || true)"
    fi
    printf '%s\t%s\t%s\t%s\n' "$component" "${component_version:-unknown}" \
        "${component_license:-UNKNOWN}" "${component_source:-unknown}" >> "$COMPONENTS_FILE"
done

UNKNOWN_LICENCES="$(awk -F'\t' '$3 == "UNKNOWN" || $3 == "custom" || $3 == "" { print "  " $1 " (" $3 ")" }' "$COMPONENTS_FILE")"
if [ -n "$UNKNOWN_LICENCES" ]; then
    # Not fatal, but it must be visible. Anything that is not a recognisable SPDX identifier
    # needs a human before the bundle is distributed — "custom" is the common case (libicu
    # carries the Unicode licence, which upstream records that way), and it is trivial to
    # resolve at packaging time and expensive to discover after distribution.
    echo "WARNING: components whose licence needs manual review before distribution:" >&2
    echo "$UNKNOWN_LICENCES" >&2
fi

# Termux architecture names are not Android ABI names, and the manifest records the Android
# one because that is what `Build.SUPPORTED_ABIS` reports on the device.
case "$TERMUX_ARCH" in
    aarch64) ABI="arm64-v8a" ;;
    arm)     ABI="armeabi-v7a" ;;
    x86_64)  ABI="x86_64" ;;
    i686)    ABI="x86" ;;
    *) echo "ERROR: unknown termux arch '$TERMUX_ARCH'" >&2; exit 1 ;;
esac

WORKDIR="${WORKDIR:-/work}"
PREFIX="${PREFIX_OVERRIDE:-/data/data/dev.mobileforge/files/usr}"
STRIP_PATH="${PREFIX#/}"          # data/data/dev.mobileforge/files/usr
OUT_DIR="${OUT_DIR:-$WORKDIR/bundles}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$OUT_DIR"

echo "==> Packaging $ID $VERSION for $ABI"
echo "    prefix: $PREFIX"

for deb in "${DEBS[@]}"; do
    [ -f "$deb" ] || { echo "ERROR: no such .deb: $deb" >&2; exit 1; }
    echo "    + $(basename "$deb")"
    dpkg-deb -x "$deb" "$STAGE/raw"
done

# Everything the bundle installs must live under the baked prefix. A .deb that puts files
# anywhere else is a packaging bug, and shipping it would scatter files outside $PREFIX on the
# device — so fail loudly rather than silently dropping them.
if [ ! -d "$STAGE/raw/$STRIP_PATH" ]; then
    echo "ERROR: no files under $PREFIX in the .debs — was the prefix repointed?" >&2
    find "$STAGE/raw" -maxdepth 4 -type d | head >&2
    exit 1
fi

OUTSIDE="$(cd "$STAGE/raw" && find . -type f -o -type l | grep -v "^\./$STRIP_PATH/" || true)"
if [ -n "$OUTSIDE" ]; then
    echo "ERROR: bundle contains files outside the prefix:" >&2
    echo "$OUTSIDE" | head >&2
    exit 1
fi

mkdir -p "$STAGE/bundle"
cp -a "$STAGE/raw/$STRIP_PATH/." "$STAGE/bundle/"

# Upstream test fixtures are not product and must not ship.
#
# termux-core installs its own unit-test binaries under libexec/installed-tests, including one
# built with AddressSanitizer that links `libclang_rt.asan-*.so` — a library no bundle has any
# business carrying. They are dead weight on a phone and they made the shared-library check fail
# for a binary nobody would ever run.
if [ -d "$STAGE/bundle/libexec/installed-tests" ]; then
    removed="$(find "$STAGE/bundle/libexec/installed-tests" -type f | wc -l)"
    rm -rf "$STAGE/bundle/libexec/installed-tests"
    echo "    removed $removed upstream test fixture(s)"
fi

# PROVIDE_SH=<shell> creates $PREFIX/bin/sh pointing at it.
#
# No termux .deb provides bin/sh — Termux's bootstrap archive creates that symlink, and we do
# not ship a bootstrap. Without it, every script whose shebang is `#!$PREFIX/bin/sh` is
# unrunnable: corepack's shims, coreutils' own helpers, pcre2-config.
#
# Opt-in rather than automatic, because the bundle is otherwise exactly what the packages
# produced, and a tool inventing files is the kind of thing that should be asked for out loud.
# It also changes which shell the terminal uses: `ShellCommandFactory` prefers $PREFIX/bin/sh
# over Android's /system/bin/sh once one exists.
if [ -n "${PROVIDE_SH:-}" ]; then
    if [ ! -e "$STAGE/bundle/bin/$PROVIDE_SH" ]; then
        echo "ERROR: PROVIDE_SH=$PROVIDE_SH but bin/$PROVIDE_SH is not in the bundle" >&2
        exit 1
    fi
    ln -sf "$PROVIDE_SH" "$STAGE/bundle/bin/sh"
    echo "    provided bin/sh -> $PROVIDE_SH"
fi

# Every ELF must actually be the architecture the manifest claims.
#
# This matters most for the builds that cannot be run here: an x86_64 emulator can execute an
# x86_64 bundle and expose a mistake immediately, whereas an aarch64 bundle is only verified
# structurally until it reaches real hardware. Shipping a bundle with the wrong machine type
# would fail on the user's device with a linker error, so it is checked at packaging time.
case "$ABI" in
    arm64-v8a)   EXPECT_MACHINE="AArch64" ;;
    armeabi-v7a) EXPECT_MACHINE="ARM" ;;
    x86_64)      EXPECT_MACHINE="X86-64" ;;
    x86)         EXPECT_MACHINE="Intel 80386" ;;
esac

MISMATCHED=""
while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    file_path="$STAGE/bundle/$candidate"
    # Only real ELF files; scripts and data are not our concern here.
    head -c 4 "$file_path" 2>/dev/null | grep -q 'ELF' || continue
    machine="$(readelf -h "$file_path" 2>/dev/null | sed -n 's/^ *Machine: *//p')"
    case "$machine" in
        *"$EXPECT_MACHINE"*) ;;
        "") ;;
        *) MISMATCHED="$MISMATCHED\n  $candidate: $machine" ;;
    esac
done <<< "$(cd "$STAGE/bundle" && find . -type f 2>/dev/null | sed 's|^\./||')"

if [ -n "$MISMATCHED" ]; then
    echo "ERROR: bundle declares $ABI ($EXPECT_MACHINE) but contains foreign binaries:" >&2
    printf '%b\n' "$MISMATCHED" >&2
    exit 1
fi
echo "    ELF machine check: all binaries are $EXPECT_MACHINE"

# Every in-prefix shebang interpreter must be present in the bundle.
#
# Scripts are built with their interpreter rewritten to this prefix — npm's is
# `#!$PREFIX/bin/env node`. If the interpreter is not also in the bundle, the script installs
# perfectly and then fails with "can't execute: Permission denied", which reads like a
# permissions or W^X problem and is nothing of the sort.
#
# npm is exactly how this was found: `env` lives in coreutils, which is not in Node's dependency
# closure, so the package manager shipped and could not start.
# Every DT_NEEDED shared library must be satisfiable.
#
# This is the failure ADR-011 is built around, and it happened for real: a rebuilt libcurl
# picked up LDAP support because openldap happened to be installed in the build prefix at the
# time, linked `libldap.so`, and did not declare it in `Depends`. The dependency closure follows
# `Depends`, so libldap was not bundled, and PHP died on the device with
#
#     CANNOT LINK EXECUTABLE ".../bin/php": library "libldap.so" not found
#
# Depending on a package's declared metadata is not enough; what the binaries actually link
# against is the truth. Android's own libraries are excluded — they are always present.
ANDROID_LIBS="libc.so libm.so libdl.so liblog.so libstdc++.so libandroid.so libz.so
libEGL.so libGLESv2.so libGLESv3.so libjnigraphics.so libOpenSLES.so libvulkan.so
libmediandk.so libnativewindow.so libsync.so libaaudio.so libcamera2ndk.so libneuralnetworks.so
ld-android.so libc++_shared.so libnativehelper.so"

BUNDLED_LIBS="$(cd "$STAGE/bundle" && find . -name '*.so*' 2>/dev/null | sed 's|.*/||' | sort -u)"
MISSING_LIBS=""
while IFS= read -r elf; do
    [ -n "$elf" ] || continue
    file_path="$STAGE/bundle/$elf"
    head -c 4 "$file_path" 2>/dev/null | grep -q 'ELF' || continue

    while IFS= read -r needed; do
        [ -n "$needed" ] || continue
        case " $ANDROID_LIBS " in *" $needed "*) continue ;; esac
        printf '%s\n' "$BUNDLED_LIBS" | grep -qxF "$needed" && continue
        MISSING_LIBS="$MISSING_LIBS\n  $elf needs $needed"
    done <<< "$(readelf -d "$file_path" 2>/dev/null |
        sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')"
done <<< "$(cd "$STAGE/bundle" && find . -type f 2>/dev/null | sed 's|^\./||')"

if [ -n "$MISSING_LIBS" ]; then
    echo "ERROR: binaries link against libraries that are not in the bundle:" >&2
    printf '%b\n' "$MISSING_LIBS" | sort -u | head -12 >&2
    echo "Add the package providing them to the closure roots." >&2
    exit 1
fi
echo "    shared library check: every NEEDED library is present"

# Interpreters the `base` bundle installs into the shared prefix.
BASE_PROVIDES="sh bash dash env"

MISSING_INTERPRETERS=""
while IFS= read -r script; do
    [ -n "$script" ] || continue
    file_path="$STAGE/bundle/$script"
    head -c 2 "$file_path" 2>/dev/null | grep -q '#!' || continue

    interpreter="$(head -c 256 "$file_path" 2>/dev/null | head -1 | sed 's|^#!\s*||' | awk '{print $1}')"
    case "$interpreter" in
        "$PREFIX"/*) ;;
        *) continue ;;   # /system/bin/sh and friends are provided by Android.
    esac

    relative="${interpreter#$PREFIX/}"
    # `-e` follows links, which is what we want: symlinks are still real in the staging tree at
    # this point (they are lifted into the manifest further down), so this resolves an
    # interpreter reached through one — `bin/env` is itself a link to `coreutils`.
    if [ ! -e "$STAGE/bundle/$relative" ]; then
        MISSING_INTERPRETERS="$MISSING_INTERPRETERS\n  $script needs $interpreter"
    fi
    # Only executable files. A documentation sample under share/doc is not something anything
    # will ever exec — util-linux ships `getopt-example.tcsh`, which would otherwise demand tcsh
    # be added to a Node bundle. The executable bit is the precise definition of "could run".
done <<< "$(cd "$STAGE/bundle" && find . -type f -perm -u+x 2>/dev/null | sed 's|^\./||')"

# REQUIRES=<bundle>[,<bundle>] records bundles that must be installed first.
#
# Tool bundles declare `base`, which carries the shell and coreutils. Interpreters those
# bundles provide are therefore expected to be absent from THIS archive and are not failures —
# but they are still listed, so the dependency is visible rather than implied.
if [ -n "${REQUIRES:-}" ] && [ -n "$MISSING_INTERPRETERS" ]; then
    for provided in $BASE_PROVIDES; do
        MISSING_INTERPRETERS="$(printf '%b
' "$MISSING_INTERPRETERS" | grep -v "/$provided\$" || true)"
    done
fi

if [ -n "$MISSING_INTERPRETERS" ]; then
    # ALLOW_MISSING_INTERPRETERS lists interpreters the bundle knowingly does without.
    #
    # Some scripts are genuinely optional: git ships CVS and Perforce bridges needing perl and
    # python, and bundling a Python runtime so that `git-p4` exists on a phone is not a trade
    # anyone would make. But shipping commands that cannot run must not be silent either — so
    # it has to be said out loud, at the call site, per interpreter.
    remaining="$MISSING_INTERPRETERS"
    for allowed in ${ALLOW_MISSING_INTERPRETERS:-}; do
        remaining="$(printf '%b\n' "$remaining" | grep -v "/$allowed\$" || true)"
    done

    if [ -n "$(printf '%b' "$remaining" | tr -d '[:space:]')" ]; then
        echo "ERROR: scripts reference interpreters that are not in the bundle:" >&2
        printf '%b\n' "$remaining" | sort -u | head -10 >&2
        echo "Add the package providing them to the closure roots, or list the interpreter in" >&2
        echo "ALLOW_MISSING_INTERPRETERS if those commands are optional." >&2
        exit 1
    fi

    echo "    shebang check: these commands will NOT run, by declared choice:"
    printf '%b\n' "$MISSING_INTERPRETERS" | sort -u | sed 's/^/    /'
else
    echo "    shebang check: all in-prefix interpreters are present"
fi

# Anything in bin/ or libexec/ has to come out executable, symlinks included — git ships ~50
# links to one binary. The installer applies this from the manifest rather than trusting the
# archive's own mode bits.
#
# Link targets count too, even when they live outside bin/ and libexec/. `bin/npm` is a symlink
# to `lib/node_modules/npm/bin/npm-cli.js`; the installer applies the executable bit from this
# list, so collecting only bin/ and libexec/ left the actual script non-executable and npm died
# with "can't execute: Permission denied" — a message that points at W^X and means nothing of
# the sort.
EXECUTABLES="$(cd "$STAGE/bundle" && {
    find bin libexec \( -type f -o -type l \) 2>/dev/null
    # Resolve each link in bin/ and libexec/ to a bundle-relative path.
    while IFS= read -r link; do
        [ -n "$link" ] || continue
        resolved="$(realpath -m --relative-to=. "$(dirname "$link")/$(readlink "$link")" 2>/dev/null)"
        case "$resolved" in
            ""|../*) ;;                       # Outside the bundle: not ours to chmod.
            *) [ -f "$resolved" ] && echo "$resolved" ;;
        esac
    done <<< "$(find bin libexec -type l 2>/dev/null)"
} | sort -u || true)"

# Symlinks are recorded in the manifest and REMOVED from the tree before zipping.
#
# `zip` follows symlinks by default and stores a full copy of each target, with no dedup. For
# git that turned a 24 MB package into a 302 MB bundle. `zip -y` would store them as links, but
# that hides the targets in archive metadata the installer cannot easily read or vet; declaring
# them in the manifest keeps them explicit and checkable.
SYMLINKS_FILE="$STAGE/symlinks.tsv"
: > "$SYMLINKS_FILE"
while IFS= read -r link; do
    [ -n "$link" ] || continue
    printf '%s\t%s\n' "$link" "$(readlink "$STAGE/bundle/$link")" >> "$SYMLINKS_FILE"
    rm -f "$STAGE/bundle/$link"
done <<< "$(cd "$STAGE/bundle" && find . -type l 2>/dev/null | sed 's|^\./||' | sort)"

echo "    symlinks: $(wc -l < "$SYMLINKS_FILE")"

ARCHIVE="$OUT_DIR/$ID-$VERSION-$ABI.zip"
rm -f "$ARCHIVE"
(cd "$STAGE/bundle" && zip -qr "$ARCHIVE" .)

SHA="$(sha256sum "$ARCHIVE" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$ARCHIVE")"

# 16 KB is claimed only when every shared object is actually aligned for it; a 4 KB-aligned
# library will not load on a 16 KB-page device (RISK-004), and over-claiming here would turn a
# clear refusal at install time into a cryptic linker failure at exec time.
PAGE_SIZE=16384
while read -r so; do
    [ -n "$so" ] || continue
    align="$(readelf -lW "$STAGE/bundle/$so" 2>/dev/null \
        | awk '$1=="LOAD"{print $NF}' | sort -u | tail -1)"
    case "$align" in
        0x4000|0x10000) ;;
        "") ;;
        *) PAGE_SIZE=4096 ;;
    esac
done <<< "$(cd "$STAGE/bundle" && find . -name '*.so*' -type f 2>/dev/null | sed 's|^\./||')"

python3 - "$ARCHIVE" "$SYMLINKS_FILE" "$COMPONENTS_FILE" <<PYTHON
import json, sys

executables = """$EXECUTABLES""".split()

symlinks = {}
with open(sys.argv[2]) as handle:
    for line in handle:
        line = line.rstrip("\n")
        if not line:
            continue
        link, _, target = line.partition("\t")
        symlinks[link] = target

components = []
with open(sys.argv[3]) as handle:
    for line in handle:
        line = line.rstrip("\n")
        if not line:
            continue
        name, version, license_id, source = line.split("\t")
        components.append({
            "name": name,
            "version": version,
            "license": license_id,
            "source_url": source,
        })

manifest = {
    "id": "$ID",
    "display_name": "$DISPLAY_NAME",
    "version": "$VERSION",
    "abi": "$ABI",
    "prefix": "$PREFIX",
    "sha256": "$SHA",
    "size_bytes": $SIZE,
    "page_size": $PAGE_SIZE,
    "executables": executables,
    "symlinks": symlinks,
    "components": components,
    "requires": [r for r in "${REQUIRES:-}".split(",") if r],
    "license": "$LICENSE",
    "source_url": "$SOURCE_URL",
}
path = sys.argv[1][:-4] + ".json"
with open(path, "w") as handle:
    json.dump(manifest, handle, indent=2)
    handle.write("\n")
print("    manifest:", path)
print("    executables:", len(executables))
print("    links:", len(symlinks))
print("    components:", len(components))
print("    licences:", " | ".join(sorted({c["license"] for c in components})))
PYTHON

echo "==> $ARCHIVE"
echo "    sha256: $SHA"
echo "    size:   $SIZE bytes"
echo "    pages:  $PAGE_SIZE"
