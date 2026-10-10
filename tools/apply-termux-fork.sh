#!/bin/bash
#
# Repoints the termux-packages build tree at this app's prefix.
#
# Runs INSIDE the termux package-builder container, once per fresh source tree.
#
# Upstream builds for `com.termux`; our bundles must be built for
# `/data/data/dev.mobileforge/files/usr` (ADR-011 — Termux packages are not
# relocatable, so the prefix is baked in at compile time). `properties.sh`
# assigns the default unconditionally, so a `-e` flag cannot override it; the
# sanctioned fork mechanism is editing the default in place ("safe to modify
# when forking", scripts/properties.sh). Everything derived — data dir, prefix —
# follows from this one variable.
#
# Idempotent: a no-op when the tree already points at us.
cd /work/termux-packages

# Python for the replacement: exact-string, no regex, no quoting traps.
python3 << 'PYEOF'
with open('scripts/properties.sh', 'r') as f:
    content = f.read()

old = 'TERMUX_APP__PACKAGE_NAME="com.termux"'
new = 'TERMUX_APP__PACKAGE_NAME="dev.mobileforge"'

if new in content:
    print("Prefix already repointed at dev.mobileforge")
elif old in content:
    content = content.replace(old, new)
    with open('scripts/properties.sh', 'w') as f:
        f.write(content)
    print("Prefix repointed: com.termux -> dev.mobileforge")
else:
    print("ERROR: package-name default not found — properties.sh changed shape")
    raise SystemExit(1)
PYEOF

# Verify
echo "=== Verification ==="
grep -n '^TERMUX_APP__PACKAGE_NAME=' scripts/properties.sh
