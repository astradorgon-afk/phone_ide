# Publishes toolchain bundles: verifies every archive against its manifest, stages a directory
# that is a valid bundle host, and optionally hands it to an upload command.
#
# Why this exists rather than "copy the folder up":
#
#   * A manifest whose `sha256` does not match its archive installs on exactly zero devices, and
#     the failure only shows up after a user presses "Download and install". Verifying here means
#     it fails on the machine that can still fix it.
#   * A host is only complete when every `<path>.json` has a matching `<path>.zip`. Staging both
#     together is what `CatalogInstaller` assumes (it fetches the manifest first, then the
#     archive at the same base path).
#   * `base_url` is baked into the shipped catalog. If it still says the placeholder, every
#     device installed from that APK fetches from a host that does not exist — so the catalog is
#     rewritten here, in the same step that publishes, and refuses to emit a placeholder URL.
#
# Usage:
#   ./tools/publish-bundles.ps1 -BaseUrl https://bundles.example.com
#   ./tools/publish-bundles.ps1 -BaseUrl https://bundles.example.com -UploadCommand 'aws s3 sync {out} s3://my-bucket --delete'
#
# The `-UploadCommand` placeholder {out} is replaced with the staging directory. Nothing is
# uploaded unless you pass one; by default this only stages and verifies.

[CmdletBinding()]
param(
    # Public URL the bundles are served from. Must be https (or a loopback http host for a dry run).
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    # Where to stage the publishable tree. Defaults under build/ so it is never mistaken for source.
    [string]$OutDir,

    # Where the catalog that ships in the APK lives, rewritten to point at $BaseUrl.
    [string]$CatalogPath,

    # Optional last step; runs with {out} replaced by the staging directory.
    [string]$UploadCommand
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $repo 'build/bundle-site' }
if (-not $CatalogPath) { $CatalogPath = Join-Path $repo 'app/src/main/assets/bundles/catalog.json' }

# Both places a bundle can live. The build dir wins when a name appears twice, because that is
# what the build scripts just produced.
$sourceDirs = @(
    (Join-Path $repo 'build/toolchain-bundles'),
    (Join-Path $repo 'app/src/main/assets/bundles')
)

# An https base URL is the only thing a device will accept from a real host; loopback http is
# allowed because that is how the debug catalog works during development.
if ($BaseUrl -notmatch '^https://' -and $BaseUrl -notmatch '^http://(127\.0\.0\.1|localhost|10\.0\.2\.2)(:\d+)?/?$') {
    throw "BaseUrl '$BaseUrl' must be https, or http on localhost/127.0.0.1/10.0.2.2 for a dry run."
}

Write-Host "Publishing bundles to $BaseUrl"
Write-Host "  staging: $OutDir"

if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Gather one entry per bundle path, preferring the build output when both exist.
$chosen = @{}
foreach ($dir in $sourceDirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Filter '*.zip' -File | ForEach-Object {
        $chosen[$_.BaseName] = $_.FullName
    }
}

if ($chosen.Count -eq 0) {
    throw 'No bundles found. Run the build scripts (e.g. tools/build-base-php.sh) first.'
}

$failures = @()
$published = @()

foreach ($name in ($chosen.Keys | Sort-Object)) {
    $zipPath = $chosen[$name]
    $jsonPath = [System.IO.Path]::ChangeExtension($zipPath, '.json')

    if (-not (Test-Path $jsonPath)) {
        $failures += "$name.zip has no manifest beside it"
        continue
    }

    $manifest = Get-Content $jsonPath -Raw | ConvertFrom-Json

    # The manifest must describe the archive next to it, not some other build.
    $actualSha = (Get-FileHash -Algorithm SHA256 -Path $zipPath).Hash.ToLowerInvariant()
    $actualSize = (Get-Item $zipPath).Length
    $expectedSha = ("$($manifest.sha256)").ToLowerInvariant()

    if ($actualSha -ne $expectedSha) {
        $failures += "${name}: sha256 mismatch (manifest $expectedSha, archive $actualSha)"
        continue
    }
    if ($manifest.size_bytes -and [int64]$manifest.size_bytes -ne $actualSize) {
        $failures += "${name}: size mismatch (manifest $($manifest.size_bytes), archive $actualSize)"
        continue
    }

    Copy-Item $zipPath (Join-Path $OutDir "$name.zip")
    Copy-Item $jsonPath (Join-Path $OutDir "$name.json")
    $published += [pscustomobject]@{
        Name    = $name
        Id      = $manifest.id
        Version = $manifest.version
        Abi     = $manifest.abi
        Size    = $actualSize
    }
}

if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host 'Refusing to publish — these would fail on devices:' -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}

# Rewrite the shipped catalog so the APK and the host agree. A placeholder here is the single
# most likely reason a release installs nothing, so it is refused rather than warned about.
$catalog = Get-Content $CatalogPath -Raw | ConvertFrom-Json
$catalog.base_url = $BaseUrl.TrimEnd('/')
$catalog | ConvertTo-Json -Depth 10 | Set-Content -Path $CatalogPath -Encoding UTF8
Write-Host ''
Write-Host "Catalog updated: $CatalogPath -> $BaseUrl" -ForegroundColor Green

# A copy of the catalog beside the bundles is convenient for other tooling and for humans
# checking "what is supposed to be here?".
Copy-Item $CatalogPath (Join-Path $OutDir 'catalog.json')

Write-Host ''
Write-Host "Verified and staged $($published.Count) bundle(s):"
$published | Sort-Object Name | Format-Table -AutoSize | Out-String | Write-Host

Write-Host 'Next steps:'
Write-Host "  1. Upload $OutDir to $BaseUrl so each <path>.json and <path>.zip is reachable."
Write-Host '     e.g.  aws s3 sync <out> s3://bucket --delete'
Write-Host '           rsync -av <out>/ user@host:/var/www/bundles/'
Write-Host '           gh release upload <tag> <out>/*'
Write-Host '  2. Rebuild and ship the APK so the new catalog (with this base_url) goes out.'
Write-Host '  3. On a device: Toolchains -> Download and install.'

if ($UploadCommand) {
    Write-Host ''
    Write-Host "Running: $($UploadCommand.Replace('{out}', $OutDir))"
    $expanded = $UploadCommand.Replace('{out}', $OutDir)
    & cmd /c $expanded
    if ($LASTEXITCODE -ne 0) { throw "Upload command failed with exit code $LASTEXITCODE" }
}
