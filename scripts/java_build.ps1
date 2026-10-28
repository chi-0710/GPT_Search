# Serialized host Maven build for the Java migration.
#
# All agents must run Maven through this script: it pins the bundled JDK 21, reuses
# the already-populated backend-java/.m2 repository, and takes a workspace-wide lock
# so concurrent `mvn test` runs never corrupt backend-java/target. Builds that need
# Docker (scripts/test_java_migration.ps1) must not overlap with this lock.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MvnArgs = @('test')
)
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = Join-Path $root 'backend-java\.tools\jdk-21.0.12.1+1'
$mvn = Join-Path $root 'backend-java\.tools\apache-maven-3.9.9\bin\mvn.cmd'
$repo = Join-Path $root 'backend-java\.m2\.m2\repository'
$project = Join-Path $root 'backend-java'
$lock = Join-Path $root 'backend-java\.mvn-lock'

foreach ($required in @($javaHome, $mvn, $repo, (Join-Path $project 'pom.xml'))) {
    if (-not (Test-Path $required)) { throw "缺少构建依赖：$required" }
}

$deadline = (Get-Date).AddMinutes(30)
while (-not (New-Item -ItemType Directory -Path $lock -ErrorAction SilentlyContinue)) {
    if ((Get-Date) -gt $deadline) { throw "等待 Maven 锁超时：$lock" }
    Start-Sleep -Seconds 3
}
$code = 1
try {
    $env:JAVA_HOME = $javaHome
    $env:PATH = "$javaHome\bin;" + $env:PATH
    Push-Location $project
    try {
        & $mvn -B -ntp -s maven-settings.xml "-Dmaven.repo.local=$repo" @MvnArgs
        $code = $LASTEXITCODE
    }
    finally { Pop-Location }
}
finally {
    Remove-Item $lock -Recurse -Force -ErrorAction SilentlyContinue
}
exit $code
