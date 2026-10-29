[CmdletBinding()]
param(
    [string]$JarPath = "",
    [string]$Image = "gptresearcher/gpt-researcher"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repositoryRoot "backend-java"
$stagingDirectory = Join-Path $backendRoot "target-image"
$stagedJar = Join-Path $stagingDirectory "app.jar"

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $candidate = Get-ChildItem -LiteralPath (Join-Path $backendRoot "target") `
        -Filter "researchrunner-api-*.jar" `
        -File `
        -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if ($null -eq $candidate) {
        throw "未找到已构建的 Java JAR。请先运行 Maven verify，或通过 -JarPath 指定已验证的 JAR。"
    }

    $JarPath = $candidate.FullName
}

$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
if ([System.IO.Path]::GetExtension($resolvedJar) -ne ".jar") {
    throw "-JarPath 必须指向 JAR 文件：$resolvedJar"
}

New-Item -ItemType Directory -Path $stagingDirectory -Force | Out-Null

try {
    Copy-Item -LiteralPath $resolvedJar -Destination $stagedJar -Force
    & docker build `
        --pull=false `
        --file (Join-Path $backendRoot "Dockerfile.runtime") `
        --tag $Image `
        $backendRoot

    if ($LASTEXITCODE -ne 0) {
        throw "离线 Java 运行镜像构建失败，docker build 退出码：$LASTEXITCODE"
    }
}
finally {
    if (Test-Path -LiteralPath $stagedJar) {
        Remove-Item -LiteralPath $stagedJar -Force
    }
}

Write-Host "已从经过验证的 JAR 构建运行镜像：$Image"
