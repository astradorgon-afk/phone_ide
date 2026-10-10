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

# Pinned SHA-256 of the LICENSE file at the matching tag, measured 2026-10-10.
COMPOSER_LICENSE_SHA256=7855ac293067aebe7e51afdd23b9dea54b8be24187dbecc9b9142581c37f596c

termux_step_post_get_source() {
	termux_download "$TERMUX_PKG_SRCURL" "$TERMUX_PKG_SRCDIR/composer.phar" "$TERMUX_PKG_SHA256"
	termux_download "https://raw.githubusercontent.com/composer/composer/${TERMUX_PKG_VERSION}/LICENSE" \
		"$TERMUX_PKG_SRCDIR/LICENSE" "$COMPOSER_LICENSE_SHA256"
}

termux_step_make_install() {
	install -Dm755 "$TERMUX_PKG_SRCDIR/composer.phar" "$TERMUX_PREFIX/bin/composer"
	# Fix shebang to use our PHP — the phar ships with #!/usr/bin/env php
	# which does not resolve on Android (no /usr/bin/env).
	sed -i "1s|.*|#!$TERMUX_PREFIX/bin/php|" "$TERMUX_PREFIX/bin/composer"
}
