<#
.SYNOPSIS
    Serves toolchain bundles over HTTP so the app can install them without a file picker.

.DESCRIPTION
    The app installs toolchains by fetching a catalog of bundles from a source URL. There is
    no public bundle host yet, so the intended way to exercise this is to serve the bundles
    built on this machine and point the app at it.

    Two roots are searched, newest first:
      * build/toolchain-bundles   - bundles produced by tools/build-bundle.sh
      * app/src/main/assets/bundles - bundles embedded in the APK (base, composer)

    The listener binds to localhost, which is reachable from a USB-connected device through
    `adb reverse` (the script prints the exact line). Cleartext is delivered to localhost
    only; see docs/bundle-hosting.md.

    In the app: Toolchains -> Advanced options -> Source URL, then "Download and install".

.PARAMETER Port
    Port to listen on. Must match the "adb reverse tcp:<Port> tcp:<Port>" you run.
#>
param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

$roots = @(
    (Join-Path $PSScriptRoot "..\build\toolchain-bundles"),
    (Join-Path $PSScriptRoot "..\app\src\main\assets\bundles")
) | Where-Object { Test-Path $_ } | ForEach-Object { (Resolve-Path $_).Path }

if ($roots.Count -eq 0) {
    Write-Error "No bundle directories found. Build a bundle first (tools/build-bundle.sh)."
}

$contentTypes = @{
    ".json" = "application/json"
    ".zip"  = "application/zip"
}

function Find-BundleFile([string]$relative) {
    # Reject any traversal before joining: the server hands out files by name.
    if ($relative -match "\.\.") { return $null }
    foreach ($root in $roots) {
        $candidate = Join-Path $root $relative
        if (Test-Path $candidate -PathType Leaf) { return $candidate }
    }
    return $null
}

$listener = [System.Net.HttpListener]::new()
# Both spellings: HttpListener rejects a request whose Host header matches no registered
# prefix, and the app's source URL is deliberately the literal loopback address.
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()

Write-Host "Serving bundles from:" -ForegroundColor Cyan
$roots | ForEach-Object { Write-Host "  $_" }
Write-Host ""
Write-Host "Bundle source URL for a USB device (after running the adb line below):" -ForegroundColor Green
Write-Host "  http://127.0.0.1:$Port"
Write-Host "  adb reverse tcp:$Port tcp:$Port" -ForegroundColor Yellow
Write-Host ""
Write-Host "Press Ctrl+C to stop."

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            $relative = $context.Request.Url.LocalPath.TrimStart("/")
            $file = Find-BundleFile $relative
            if ($file) {
                $bytes = [System.IO.File]::ReadAllBytes($file)
                $extension = [System.IO.Path]::GetExtension($file)
                $context.Response.ContentType = if ($contentTypes[$extension]) { $contentTypes[$extension] } else { "application/octet-stream" }
                $context.Response.ContentLength64 = $bytes.Length
                $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                Write-Host "200 $relative ($($bytes.Length) bytes)"
            } else {
                $context.Response.StatusCode = 404
                Write-Host "404 $relative" -ForegroundColor DarkGray
            }
        } catch {
            Write-Host "500 $($_.Exception.Message)" -ForegroundColor Red
            $context.Response.StatusCode = 500
        } finally {
            $context.Response.Close()
        }
    }
} finally {
    $listener.Stop()
}
