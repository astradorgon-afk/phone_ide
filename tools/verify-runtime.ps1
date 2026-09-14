# PowerShell wrapper for tools/verify-runtime.sh
#
# On Windows, `bash` on PATH is usually C:\Windows\System32\bash.exe — the WSL launcher, which
# fails with "execvpe(/bin/bash) failed" when no WSL distro is installed. This finds the real
# Git Bash instead.
#
# Usage:  .\tools\verify-runtime.ps1

$ErrorActionPreference = 'Stop'

$candidates = @(
    'C:\Program Files\Git\bin\bash.exe',
    'C:\Program Files (x86)\Git\bin\bash.exe',
    "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
)

$bash = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $bash) {
    Write-Error @'
Could not find Git Bash.

Install Git for Windows (https://git-scm.com/download/win), or run the verification
steps manually:

  adb devices
  .\gradlew :app:connectedDebugAndroidTest `
      -Pandroid.testInstrumentationRunnerArguments.class=dev.mobileforge.runtime.ExecMechanismVerificationTest
  adb logcat -d -s MF.ExecVerify
'@
    exit 1
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    & $bash 'tools/verify-runtime.sh'
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
