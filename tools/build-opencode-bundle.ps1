<#
.SYNOPSIS
    Assembles an OpenCode toolchain bundle from the upstream musl binaries.

.DESCRIPTION
    OpenCode is a Bun-compiled, musl-linked binary. It cannot be run the way the rest of the
    toolchain is: Android refuses to exec files under $PREFIX (W^X, targetSdk >= 29) and bionic's
    linker cannot load the musl dynamic loader. The one place exec is allowed is the APK's native
    library directory, so the loader itself ships there (as jniLibs/libldmusl.so) and the bundle
    carries only what the loader maps: the opencode binary and its two shared libraries.

    A second obstacle is the app seccomp filter, which is an allowlist. Bun calls close_range(2),
    which the Android 11 allowlist predates, and the legacy path syscalls (open/stat/access/mkdir)
    that native code is expected to spell as *at(). Both raise SIGSYS and kill the process, so the
    wrapper also preloads libsigsys-shim.so: a tiny musl shared object (built from
    tools/sigsys-shim/sigsys_shim.c) whose SIGSYS handler forwards those traps to allowed
    equivalents. See that file's header for the details.

    Layout produced (rooted at $PREFIX):

        bin/opencode                        wrapper: preload shim, exec the loader on the real binary
        lib/opencode/opencode               the Bun/OpenCode ELF (ET_EXEC)
        lib/opencode/libstdc++.so.6         musl-built
        lib/opencode/libgcc_s.so.1          musl-built
        lib/opencode/libsigsys-shim.so      seccomp SIGSYS forwarding shim (musl-built)

.PARAMETER Abi
    x86_64 or arm64-v8a.

.PARAMETER Work
    Staging root produced by the fetch step. Expected at <Work>\<ocarch>\...

.EXAMPLE
    pwsh tools/build-opencode-bundle.ps1 -Abi x86_64
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet("x86_64", "arm64-v8a")][string]$Abi,
    [string]$Work = "$env:TEMP\opencode\work",
    [string]$Out = $(if ($PSCommandPath) { Join-Path (Split-Path -Parent $PSCommandPath) "..\build\toolchain-bundles" } else { "build\toolchain-bundles" }),
    [string]$Version = "1.18.35"
)

$ErrorActionPreference = "Stop"

$archDir = if ($Abi -eq "x86_64") { "x86_64" } else { "aarch64" }

$oc = Join-Path $Work "$archDir\oc\opencode"
$ldstdcpp = Join-Path $Work "$archDir\libstdcpp\usr\lib\libstdc++.so.6.0.34"
$ldgcc = Join-Path $Work "$archDir\libgcc\usr\lib\libgcc_s.so.1"
$shim = Join-Path $PSScriptRoot "sigsys-shim\libsigsys-shim-$archDir.so"
foreach ($f in @($oc, $ldstdcpp, $ldgcc, $shim)) {
    if (-not (Test-Path $f)) { throw "missing input: $f (run the fetch step / build the shim first)" }
}

$staging = Join-Path $Work "$archDir\staging"
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory -Force -Path "$staging\bin", "$staging\lib\opencode" | Out-Null

# The wrapper is identical for every ABI: the loader lives in the APK's native library
# directory, whose name is supplied by the runtime as MOBILEFORGE_NATIVE_LIB_DIR.
$wrapper = @(
    '#!/system/bin/sh'
    '#'
    '# OpenCode launcher.'
    '#'
    '# OpenCode is a musl-linked, non-PIE (ET_EXEC) binary. Android will not exec files under'
    '# $PREFIX (W^X for targetSdk >= 29), and bionic''s linker cannot load the musl loader, so'
    '# the loader is packaged in the APK''s native library directory -- the one readable place'
    '# where exec is still permitted -- and invoked from there. The loader then maps opencode'
    '# out of $PREFIX, which needs only read access.'
    'DIR=/data/data/dev.mobileforge/files/usr/lib/opencode'
    'LOADER="${MOBILEFORGE_NATIVE_LIB_DIR}/libldmusl.so"'
    'if [ -z "$MOBILEFORGE_NATIVE_LIB_DIR" ] || [ ! -e "$LOADER" ]; then'
    '  echo "opencode: this app build is missing its native launcher (libldmusl.so)." >&2'
    '  exit 127'
    'fi'
    '# libmfexec is for Android binaries under $PREFIX; the musl process must not inherit it.'
    'unset LD_PRELOAD'
    'unset LD_LIBRARY_PATH'
    '# Bun defaults its temp directory to /tmp, which does not exist (and cannot be created) on'
    '# Android. Point every temp convention at a writable directory inside $PREFIX.'
    'PREFIX=/data/data/dev.mobileforge/files/usr'
    'TMPDIR="$PREFIX/tmp"'
    'TMP="$TMPDIR"'
    'export TMPDIR TMP'
    'mkdir -p "$TMPDIR" 2>/dev/null'
    '# Android''s app seccomp policy is an allowlist that TRAPs unknown syscalls with SIGSYS.'
    '# Bun calls close_range(2) during startup, which Android 11 predates, so it would die with'
    '# "Bad system call". The shim installs a SIGSYS handler that forwards the blocked legacy'
    '# path syscalls to their *at() equivalents and turns the rest into clean failures.'
    'LD_PRELOAD="$DIR/libsigsys-shim.so"'
    'export LD_PRELOAD'
    'exec "$LOADER" --library-path "$DIR" "$DIR/opencode" "$@"'
    ''
) -join "`n"
[System.IO.File]::WriteAllText("$staging\bin\opencode", $wrapper, (New-Object System.Text.UTF8Encoding $false))

Copy-Item $oc "$staging\lib\opencode\opencode"
Copy-Item $ldstdcpp "$staging\lib\opencode\libstdc++.so.6"
Copy-Item $ldgcc "$staging\lib\opencode\libgcc_s.so.1"
Copy-Item $shim "$staging\lib\opencode\libsigsys-shim.so"

New-Item -ItemType Directory -Force -Path $Out | Out-Null
$zip = Join-Path $Out "opencode-$Version-$Abi.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }

# Build the archive by hand. ZipFile.CreateFromDirectory uses the platform separator for entry
# names -- backslashes on Windows -- and BundlePathPolicy rejects those as unsafe, so the bundle
# would install nowhere. Entry names must be forward slashes, always.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stagingFull = (Get-Item $staging).FullName
$fileStream = [System.IO.File]::Open($zip, [System.IO.FileMode]::Create)
$archive = New-Object System.IO.Compression.ZipArchive(
    $fileStream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $epoch = [DateTimeOffset]::new([DateTime]::new(2020, 1, 1), [TimeSpan]::Zero)
    foreach ($file in Get-ChildItem -Recurse -File $staging) {
        $relative = $file.FullName.Substring($stagingFull.Length + 1).Replace('\', '/')
        $entry = $archive.CreateEntry(
            $relative, [System.IO.Compression.CompressionLevel]::Optimal)
        $entry.LastWriteTime = $epoch
        $entryStream = $entry.Open()
        $input = [System.IO.File]::OpenRead($file.FullName)
        try { $input.CopyTo($entryStream) } finally { $input.Dispose(); $entryStream.Dispose() }
    }
} finally {
    $archive.Dispose()
    $fileStream.Dispose()
}

$size = (Get-Item $zip).Length
$sha = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()

$manifest = [ordered]@{
    id           = "opencode"
    display_name = "OpenCode"
    version      = $Version
    abi          = $Abi
    prefix       = "/data/data/dev.mobileforge/files/usr"
    sha256       = $sha
    size_bytes   = $size
    page_size    = 4096
    executables  = @(
        "bin/opencode",
        "lib/opencode/opencode",
        "lib/opencode/libstdc++.so.6",
        "lib/opencode/libgcc_s.so.1",
        "lib/opencode/libsigsys-shim.so"
    )
    symlinks = @{}
    components = @(
        [ordered]@{ name = "opencode"; version = $Version; license = "MIT"; source_url = "https://github.com/anomalyco/opencode" },
        [ordered]@{ name = "bun";      version = "1.3.14"; license = "MIT"; source_url = "https://github.com/oven-sh/bun" },
        [ordered]@{ name = "musl";     version = "1.2.6";  license = "MIT"; source_url = "https://musl.libc.org/" },
        [ordered]@{ name = "libstdc++"; version = "15.2.0"; license = "GPL-3.0-with-GCC-exception"; source_url = "https://gcc.gnu.org/" },
        [ordered]@{ name = "libgcc";    version = "15.2.0"; license = "GPL-3.0-with-GCC-exception"; source_url = "https://gcc.gnu.org/" }
    )
    requires   = @("base")
    license    = "MIT"
    source_url = "https://github.com/anomalyco/opencode"
}
$manifestJson = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText(
    (Join-Path $Out "opencode-$Version-$Abi.json"), $manifestJson,
    (New-Object System.Text.UTF8Encoding $false))

"built opencode-$Version-$Abi.zip  ($size bytes, sha256 $sha)"
"wrote opencode-$Version-$Abi.json"
