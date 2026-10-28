# Route A A0-02: local toolchain self-check for the Java migration.
# Read-only: prints versions and resolves JAVA_HOME/Maven from the repository bundle.
param(
    [string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]

function Add-Row([string]$Name, [string]$Value, [bool]$Required) {
    $rows.Add([pscustomobject]@{ Tool = $Name; Value = $Value; Required = $Required })
    if ($Required -and ($Value -match '^(missing|error)')) { $failures.Add($Name) }
}

# JDK: prefer the repository-independent JAVA_HOME, then common local installs.
$javaHome = $env:JAVA_HOME
if ($javaHome -and (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    # JAVA_HOME points at the JDK root.
} elseif (Test-Path 'F:\jdk-24\bin\java.exe') {
    $javaHome = 'F:\jdk-24'
} else {
    $javaHome = $null
}
if ($javaHome) {
    $env:JAVA_HOME = $javaHome
    $javaVersion = (& (Join-Path $javaHome 'bin\java.exe') -version 2>&1 | Select-Object -First 1) -replace '.*version "([^"]+)".*', '$1'
    $major = [int]($javaVersion.Split('.')[0])
    if ($major -lt 21) { Add-Row 'JDK' "error ($javaVersion, need 21+)" $true } else { Add-Row 'JDK' "$javaVersion ($javaHome)" $true }
} else {
    Add-Row 'JDK' 'missing' $true
}

# Maven: use the bundled distribution, do not require PATH.
$mvn = Join-Path $Workspace 'backend-java\.tools\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path $mvn)) { $mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source }
if ($mvn -and (Test-Path $mvn)) {
    $mvnVersion = (& $mvn -v 2>&1 | Select-Object -First 1) -replace 'Apache Maven ', ''
    Add-Row 'Maven' "$mvnVersion ($mvn)" $true
} else {
    Add-Row 'Maven' 'missing' $true
}

# Python is optional: it is only used by the local E2E provider stub
# (scripts/stub_provider_server.py); the Java backend itself never needs Python.
if (Get-Command python -ErrorAction SilentlyContinue) {
    Add-Row 'Python (E2E stub only, optional)' ((& python --version 2>&1) -replace 'Python ', '') $false
} else {
    Add-Row 'Python (E2E stub only, optional)' 'missing (optional)' $false
}

if (Get-Command node -ErrorAction SilentlyContinue) {
    Add-Row 'Node' (& node --version 2>&1) $true
} else {
    Add-Row 'Node' 'missing' $true
}

if (Get-Command git -ErrorAction SilentlyContinue) {
    Add-Row 'Git' ((& git --version 2>&1) -replace 'git version ', '') $true
} else {
    Add-Row 'Git' 'missing' $true
}

# Docker is optional for unit tests but required for A0-05/A1-02/A6-06.
try {
    $docker = (& docker version --format '{{.Server.Version}}' 2>&1 | Select-Object -First 1)
    if ($LASTEXITCODE -eq 0 -and $docker -match '^\d+\.') { Add-Row 'Docker engine' $docker $false }
    else { Add-Row 'Docker engine' 'unavailable (daemon or named-pipe access)' $false }
} catch {
    Add-Row 'Docker engine' 'unavailable' $false
}

$rows | Format-Table -AutoSize
if ($failures.Count -gt 0) {
    Write-Error ("Missing required tools: " + ($failures -join ', '))
    exit 1
}
Write-Output 'Toolchain OK (Docker is optional for M0 unit work).'
exit 0
