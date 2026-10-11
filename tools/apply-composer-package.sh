#!/bin/bash
#
# Installs the Composer package recipe into the termux-packages source tree.
#
# Runs INSIDE the termux package-builder container. See docs/adr/ADR-011-toolchain-strategy.md.
#
# Composer is a PHP phar — the platform (PHP, installer, interposer) all work.
# This is packaging, not a platform problem. The recipe downloads the phar and
# installs it untouched under lib/, with a tiny shell wrapper at bin/composer
# that runs it through our PHP. It deliberately does NOT rewrite the phar.
#
# It used to: the phar ships `#!/usr/bin/env php`, which does not resolve on
# Android, so line 1 was `sed`-edited to point at $PREFIX/bin/php. That breaks
# the phar. A phar's SHA-512 signature is computed over the whole file — the
# stub and its shebang included — so the edit made every run fail with
# "SHA512 signature could not be verified". Leaving the bytes alone also means
# PHP re-verifies that signature on every invocation, which is a real integrity
# check rather than a formality. Two things must NOT touch the bytes: our recipe
# (it doesn't anymore) and the build system itself — termux's massage pass
# rewrites every shebang it finds, so the package excludes the phar from it with
# TERMUX_PKG_NO_SHEBANG_FIX_FILES below.
#
# Usage (from the host):
#   docker run --rm --device /dev/fuse --cap-add CAP_SYS_ADMIN \
#     --security-opt apparmor=unconfined -v mf-termux:/work \
#     -v "<repo>/tools:/tools:ro" ghcr.io/termux/package-builder:latest \
#     bash /tools/apply-composer-package.sh
set -euo pipefail

SRC="/work/termux-packages"
PKG_DIR="$SRC/packages/composer"

if [ ! -d "$SRC" ]; then
    echo "ERROR: no termux-packages source tree at $SRC" >&2
    exit 1
fi

mkdir -p "$PKG_DIR"

# Upstream ships its own composer recipe (a from-source build needing a bootstrap
# composer+php); we intentionally replace it with a pinned-phar recipe, so remove its
# leftover patch — otherwise the build tries to patch source we never check out.
rm -f "$PKG_DIR/fix-shebang.patch"

cat > "$PKG_DIR/build.sh" <<'BUILD'
TERMUX_PKG_HOMEPAGE=https://getcomposer.org/
TERMUX_PKG_DESCRIPTION="Dependency Manager for PHP"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_VERSION=2.10.3
TERMUX_PKG_SRCURL=https://getcomposer.org/download/${TERMUX_PKG_VERSION}/composer.phar
TERMUX_PKG_SHA256=7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6
TERMUX_PKG_RECOMMENDS="php"
TERMUX_PKG_CONFLICTS="composer-php"
TERMUX_PKG_LICENSE_FILE="LICENSE"
# A PHP phar, not an archive: fetch it as a file instead of tar-extracting it,
# and mark it arch-independent — the same bytes serve every ABI.
TERMUX_PKG_SKIP_SRC_EXTRACT=true
TERMUX_PKG_PLATFORM_INDEPENDENT=true
# The build's own massage pass rewrites every `#!` shebang it finds under bin/ and lib/ to the
# termux prefix — good for scripts, corruption for a signed phar (its SHA-512 signature covers
# the whole file, shebang included). Exempt the phar from that pass. The wrapper's shebang is
# already $PREFIX-correct (and carries a $PREFIX/bin/php *exec*, never a /usr/bin one), so nothing
# else in this package needs the fix.
TERMUX_PKG_NO_SHEBANG_FIX_FILES="lib/composer/composer.phar"

# Pinned SHA-256 of the LICENSE file at the matching tag, measured 2026-10-10.
COMPOSER_LICENSE_SHA256=7855ac293067aebe7e51afdd23b9dea54b8be24187dbecc9b9142581c37f596c

termux_step_post_get_source() {
	termux_download "$TERMUX_PKG_SRCURL" "$TERMUX_PKG_SRCDIR/composer.phar" "$TERMUX_PKG_SHA256"
	termux_download "https://raw.githubusercontent.com/composer/composer/${TERMUX_PKG_VERSION}/LICENSE" \
		"$TERMUX_PKG_SRCDIR/LICENSE" "$COMPOSER_LICENSE_SHA256"
}

termux_step_make_install() {
	# The phar is installed byte-for-byte. Do not edit it — see the header comment.
	install -Dm644 "$TERMUX_PKG_SRCDIR/composer.phar" "$TERMUX_PREFIX/lib/composer/composer.phar"

	# `composer` on PATH is this wrapper, not the phar: it runs the phar through our PHP, so the
	# phar is never executed by its own (unresolvable on Android) shebang.
	mkdir -p "$TERMUX_PREFIX/bin"
	cat > "$TERMUX_PREFIX/bin/composer" <<EOF
#!$TERMUX_PREFIX/bin/sh
exec "$TERMUX_PREFIX/bin/php" "$TERMUX_PREFIX/lib/composer/composer.phar" "\$@"
EOF
	chmod 755 "$TERMUX_PREFIX/bin/composer"
}
BUILD

echo "==> Composer package recipe installed at $PKG_DIR/build.sh"
cat "$PKG_DIR/build.sh"
