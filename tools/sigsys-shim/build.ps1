<#
.SYNOPSIS
    Builds the musl seccomp shim that the OpenCode bundle preloads.

.DESCRIPTION
    The shim (sigsys_shim.c) must be a musl shared object: it is loaded by the musl dynamic
    loader inside the opencode process, so it cannot be built against bionic. This script
    compiles it for both ABIs using Alpine (musl) in Docker and drops the results next to the
    source, where tools/build-opencode-bundle.ps1 expects them:

        libsigsys-shim-x86_64.so     for the x86_64 bundle
        libsigsys-shim-aarch64.so    for the arm64-v8a bundle

    Docker Desktop runs the arm64 build under emulation, so no cross toolchain is needed.

.EXAMPLE
    pwsh tools/sigsys-shim/build.ps1
#>
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $PSCommandPath

if (-not (Test-Path (Join-Path $here "sigsys_shim.c"))) {
    throw "sigsys_shim.c not found next to $PSCommandPath"
}

function Build-Shim {
    param([string]$Platform, [string]$Output)

    Write-Host "building $Output ($Platform)" -ForegroundColor Cyan
    $dockerArgs = @("run", "--rm")
    if ($Platform -ne "amd64") {
        $dockerArgs += @("--platform", "linux/$Platform")
    }
    $script = "apk add --no-cache build-base >/dev/null 2>&1 && " +
              "gcc -shared -fPIC -O2 -Wall -Wl,-soname,libsigsys-shim.so " +
              "-o /src/$Output /src/sigsys_shim.c"
    $dockerArgs += @("-v", "${here}:/src", "alpine:latest", "sh", "-c", $script)
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker build of $Output failed"
    }
}

Build-Shim -Platform "amd64" -Output "libsigsys-shim-x86_64.so"
Build-Shim -Platform "arm64" -Output "libsigsys-shim-aarch64.so"

Write-Host ""
Get-ChildItem $here -Filter "libsigsys-shim-*.so" |
    ForEach-Object { Write-Host "  $($_.Name)  $($_.Length) bytes" -ForegroundColor Green }
