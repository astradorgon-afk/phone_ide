#!/bin/bash
cd /work/termux-packages

# Use Python to do the replacement (more reliable for multi-line)
python3 << 'PYEOF'
with open('packages/nodejs/build.sh', 'r') as f:
    content = f.read()

# Find and replace the LLVM_TAR block - match exact with tabs
old_block = '''# Also the sha256sum is the hash of the tarball, which we can directly use
	local LLVM_TAR="clang-llvmorg-23-init-484-gf646b915-1.tar.xz"
	local LLVM_TAR_HASH=1c3c056427ab0db261c54c8fdf7c8404ff55e3de3e550520bcb1e1660ca05aad'''

new_block = '''# Also the sha256sum is the hash of the tarball, which we can directly use
# Use LLVM 21 for x86_64 due to V8 macro incompatibility with LLVM 23 Clang
# (deps/v8/src/roots/roots.h:504: error: expected identifier in INTERNALIZED_STRING_ROOT_LIST)
# arm64 works with LLVM 23.
	if [ "$TERMUX_ARCH" = "x86_64" ]; then
	    local LLVM_TAR="clang-llvmorg-21-init-5118-g52cd27e6-5.tar.xz"
	    local LLVM_TAR_HASH=790fcc5b04e96882e8227ba7994161ab945c0e096057fc165a0f71e32a7cb061
	else
	    local LLVM_TAR="clang-llvmorg-23-init-484-gf646b915-1.tar.xz"
	    local LLVM_TAR_HASH=1c3c056427ab0db261c54c8fdf7c8404ff55e3de3e550520bcb1e1660ca05aad
	fi'''

if old_block in content:
    content = content.replace(old_block, new_block)
    with open('packages/nodejs/build.sh', 'w') as f:
        f.write(content)
    print("Patch applied successfully")
else:
    print("ERROR: Could not find target block")
PYEOF

# Verify
echo "=== Verification ==="
grep -A 25 'Also the sha256sum is the hash' packages/nodejs/build.sh